package com.summit.runtime.loop;

import com.summit.core.agent.CompactionState;
import com.summit.core.agent.Execution;
import com.summit.core.compact.ContextCompactRequest;
import com.summit.core.compact.ContextCompacter;
import com.summit.core.compact.ContextSqueezeRequest;
import com.summit.core.compact.Tokenizer;
import com.summit.core.conversation.ConversationManager;
import com.summit.core.exception.MaxStepsExceededException;
import com.summit.core.runtime.loop.CheckPointResult;
import com.summit.core.runtime.loop.RuntimeBoundaryChecker;
import com.summit.runtime.agent.AgentConfig;
import com.summit.runtime.compact.DefaultManualCompacter;
import com.summit.runtime.compact.DefaultModelCompacter;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;


/**
 * Per-execution runtime boundary checker.
 *
 * <p>Context-compaction responsibility split: this class only <b>judges the band</b> (against the
 * progressive thresholds — local truncation or model compaction). Once a band is hit, the actual
 * blocking compaction is delegated to the matching {@link ContextCompacter} implementation (manual and
 * model). By the time it returns, and before the main loop starts its next round, the session context
 * is ready.</p>
 *
 * <p>This class is also the <b>single writer</b> of the summarize counters on
 * {@link Execution#getCompactionState()}: the attempt counter is incremented before the compact model
 * is called (a throwing call is an attempt too), reset only when the context is observed back below
 * the model threshold, and the "summarization is closed" latch, once set, is never cleared for the
 * execution. Without that gate a failing compact model is retried at every checkpoint — twice per
 * round, each attempt blocking for the full model timeout.</p>
 */
@RequiredArgsConstructor
@Slf4j
public class  BoundaryChecker implements RuntimeBoundaryChecker {

    /** Fallback truncation threshold when the policy is not configured. */
    private static final double DEFAULT_TRUNCATE_THRESHOLD = 0.7;
    /** Fallback model-squeeze threshold when the policy is not configured. */
    private static final double DEFAULT_MODEL_THRESHOLD = 0.85;
    /** Fallback rounds per local truncation pass when the policy is not configured (matches AgentConfig.OriginalSqueeze#defaultPolicy). */
    private static final int DEFAULT_TRUNCATE_TURN = 10;

    private final AgentConfig agentConfig;
    private final Tokenizer tokenizer;
    private final ConversationManager conversationManager;
    /** Manual per-round truncation compaction (local band). */
    private final DefaultManualCompacter manualCompacter;
    /** Model deep compaction (model band). */
    private final DefaultModelCompacter modelCompacter;


    @Override
    public CheckPointResult before(Execution execution) {
        checkModelAttempt(execution);
        compactIfNeeded(execution);
        return CheckPointResult.continueWith();
    }

    @Override
    public void checkModelAttempt(Execution execution) {
        if (execution.getModelAttempts() >= effectiveMaxSteps(execution)) {
            throw new MaxStepsExceededException("loop exceeded maximum steps");
        }
    }

    @Override
    public CheckPointResult after(Execution execution) {
        compactIfNeeded(execution);
        return CheckPointResult.continueWith();
    }
    /**
     * Two-band squeeze decision based on {@link AgentConfig.ProgressiveSqueezePolicy}. The bands are
     * mutually exclusive and inclusive on their left end:
     * <ul>
     *   <li>ratio in [truncateThreshold, modelThreshold): the local round-based truncation band —
     *       {@code shouldSqueeze=true}, and the number of rounds squeezed per pass comes from
     *       {@code OriginalSqueeze.expectTruncateTurn};</li>
     *   <li>ratio &gt;= modelThreshold: the model-based deep compaction band —
     *       {@code expectAdvanceSqueeze=true}.</li>
     * </ul>
     * Below {@code truncateThreshold} neither band is hit. This method only reports the band; whether
     * the summarize gate actually lets a model call through is decided in {@link #compactIfNeeded}.
     */
    public ContextSqueezeRequest shouldSqueezeContext(ConversationManager conversationManager, Execution execution) {

        double ratio = tokenRatio(execution);

        AgentConfig.ProgressiveSqueezePolicy policy = agentConfig.squeezeThreshold();

        int truncateTurn = policy == null || policy.truncateSqueeze() == null
                ? 0 : Math.max(policy.truncateSqueeze().expectTruncateTurn(), 0);

        double original = truncateThreshold();
        double advanced = modelThreshold();
        boolean shouldTruncate = ratio >= original && ratio < advanced;

        return ContextSqueezeRequest.builder()
                .shouldSqueeze(shouldTruncate)
                .truncateTurn(shouldTruncate ? (truncateTurn > 0 ? truncateTurn : DEFAULT_TRUNCATE_TURN) : 0)
                .expectAdvanceSqueeze(ratio >= advanced)
                .build();
    }
    /**
     * Performs one blocking compaction for the squeeze band: the manual compacter truncates rounds
     * when {@code shouldSqueeze}, or the model compacter summarizes and rebuilds the session when
     * {@code expectAdvanceSqueeze} and the summarize gate is open. The model band wins when both are
     * somehow true, so a ratio past the model threshold always tries the deep compaction first.
     * Returns once the compaction is done so the agent loop can start its next round; does nothing
     * when no band is hit.
     *
     * <p>The local truncation band is deliberately untouched by the summarize gate: this slice only
     * inserts the brake in front of the model call.</p>
     */
    private void compactIfNeeded(Execution execution) {
        ContextSqueezeRequest request = shouldSqueezeContext(conversationManager, execution);
        if (request.expectAdvanceSqueeze()) {
            // The summarize band is terminal for this checkpoint: on a closed gate the local
            // truncation band does not apply either (its range ends at the model threshold), and the
            // prune fallback belongs to the next slice.
            compactBySummary(execution, request);
            return;
        }
        if (request.shouldSqueeze()) {
            compactByTruncation(execution, request);
        }
    }

    /**
     * Runs the summarize band under the attempt gate of §3.1.
     *
     * <p>Every exit path leaves the execution's compaction state consistent: exactly one attempt is
     * counted before the model call, a rebuild that brings the context back under the threshold
     * opens a new trigger window, and any failure closes summarization for the rest of the
     * execution so no further checkpoint pays for the same failing call.</p>
     */
    private void compactBySummary(Execution execution, ContextSqueezeRequest request) {
        CompactionState state = conversationManager.compactionState(execution);
        if (state.summaryUnavailable()) {
            log.info("【context-compact】summarization is closed for this execution, model compaction "
                    + "skipped: executionId={}", execution.getId());
            return;
        }
        int attemptBudget = maxSummaryAttempts();
        if (state.summaryAttempts() >= attemptBudget) {
            markSummarizationUnavailable(execution, "the summarize attempt budget (" + attemptBudget
                    + ") is spent and the context is still above the model threshold");
            return;
        }
        if (modelCompacter == null) {
            markSummarizationUnavailable(execution, "no model compacter is wired");
            return;
        }

        // §3.3.4: counted before the model call, so a call that throws is still an attempt.
        
        boolean compacted = false;
        execution.setCompactionState(state.beforeSummaryAttempt());
        try {
            compacted = modelCompacter.compact(new ContextCompactRequest(execution, request));
        } catch (RuntimeException e) {
            // The compacter reports its own failures, but an escaping one must still close the gate.
            log.warn("【context-compact】model compaction raised, summarization will be closed: executionId={}",
                    execution.getId(), e);
        }
        if (!compacted) {
            markSummarizationUnavailable(execution, "the summarize call failed or returned no usable summary");
            return;
        }

        // Re-read the state: the rebuild just advanced the summary generation, and that write must
        // not be lost by overwriting it with the pre-call snapshot.
        CompactionState afterRebuild = conversationManager.compactionState(execution);
        double ratio = tokenRatio(execution);
        if (ratio < modelThreshold()) {
            execution.setCompactionState(afterRebuild.summaryWindowReset());
            log.info("【context-compact】model compaction brought the context back under the threshold, "
                    + "attempts reset: ratio={}, attempts={}, executionId={}",
                    ratio, afterRebuild.summaryAttempts(), execution.getId());
            return;
        }
        if (afterRebuild.summaryAttempts() >= attemptBudget) {
            markSummarizationUnavailable(execution, "the summarize attempt budget (" + attemptBudget
                    + ") is spent and the rebuilt context is still at ratio=" + ratio);
            return;
        }
        log.info("【context-compact】chained summary accepted, the next checkpoint compresses this "
                        + "summary again: ratio={}, attempts={}/{}, executionId={}",
                ratio, afterRebuild.summaryAttempts(), attemptBudget, execution.getId());
    }

    /** Runs the local truncation band exactly as before; this slice only adds the summarize gate. */
    private void compactByTruncation(Execution execution, ContextSqueezeRequest request) {
        if (manualCompacter == null) {
            log.warn("【context-compact】no manual compacter wired, compression skipped: executionId={}",
                    execution.getId());
            return;
        }
        boolean compacted = manualCompacter.compact(new ContextCompactRequest(execution, request));
        log.info("【context-compact】checkpoint triggered manual band, compacted={}, executionId={}",
                compacted, execution.getId());
    }

    /**
     * Closes summarization for the rest of this execution and reports it once.
     *
     * <p>The latch is execution-level on purpose: a compact model that is unreachable, unconfigured
     * or unable to shrink the context does not become healthy again inside the same run, and every
     * further attempt costs a full model timeout.</p>
     */
    private void markSummarizationUnavailable(Execution execution, String reason) {
        execution.setCompactionState(conversationManager.compactionState(execution).summarizationUnavailable());
        log.warn("【context-compact】summarization closed for the rest of this execution ({}); later "
                + "checkpoints will not call the compact model: executionId={}", reason, execution.getId());
    }

    /** Token ratio of the current context against the configured cap. */
    private double tokenRatio(Execution execution) {
        return tokenizer.calcCurrentTokenRatio(conversationManager.messages(execution), agentConfig.maxTokens());
    }

    /** Effective truncation threshold: the configured policy value, or the fallback. */
    private double truncateThreshold() {
        AgentConfig.ProgressiveSqueezePolicy policy = agentConfig.squeezeThreshold();
        Double configured = policy == null || policy.truncateSqueeze() == null
                ? null : policy.truncateSqueeze().threshold();
        return configured == null ? DEFAULT_TRUNCATE_THRESHOLD : configured;
    }

    /** Effective model threshold: the configured policy value, or the fallback. */
    private double modelThreshold() {
        AgentConfig.ProgressiveSqueezePolicy policy = agentConfig.squeezeThreshold();
        Double configured = policy == null || policy.modelSqueeze() == null
                ? null : policy.modelSqueeze().threshold();
        return configured == null ? DEFAULT_MODEL_THRESHOLD : configured;
    }

    /** Summarize attempts allowed per trigger window; a single attempt when unconfigured. */
    private int maxSummaryAttempts() {
        return agentConfig.summaryAttemptBudget();
    }

    /**
     * Max loop iterations for this execution. {@code Execution.maxSteps} is not
     * set by {@code Execution.create}, so fall back to the agent's configured
     * iteration budget; without any budget configured the loop is unbounded.
     */
    private int effectiveMaxSteps(Execution execution) {
        if (execution.getMaxSteps() > 0) {
            return execution.getMaxSteps();
        }
        Integer maxIterations = agentConfig.maxIterations();
        return maxIterations == null ? Integer.MAX_VALUE : Math.max(maxIterations, 1);
    }
}
