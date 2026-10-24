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
import com.summit.runtime.compact.DefaultContextPruner;
import com.summit.runtime.compact.DefaultModelCompacter;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;


/**
 * Per-execution runtime boundary checker.
 *
 * <p>Context-compaction responsibility split: this class only <b>judges the band</b>; the blocking
 * compaction itself is delegated to the matching {@link ContextCompacter} implementation. By the
 * time it returns, and before the main loop starts its next round, the session context is
 * ready.</p>
 *
 * <p>The band decision follows the state machine of the compaction design: under the model
 * threshold nothing happens; at or above it summarization is attempted while it is still available;
 * once summarization is closed for the execution — it failed, no compact model is wired, its
 * attempt budget is spent while the context is still oversized — the local prune band takes over
 * whenever another checkpoint reaches the threshold. New tool groups can therefore be pruned
 * during long tasks even after an earlier fallback.</p>
 *
 * <p>This class is the <b>single writer</b> of the summarize counters on
 * {@link Execution#getCompactionState()}: the attempt counter is incremented before the compact
 * model is called (a throwing call is an attempt too), reset only when the context is observed back
 * below the model threshold, and the "summarization is closed" latch, once set, is never cleared
 * for the execution. Without that gate a failing compact model would be retried at every
 * checkpoint — twice per round, each attempt blocking for the full model timeout.</p>
 */
@RequiredArgsConstructor
@Slf4j
public class BoundaryChecker implements RuntimeBoundaryChecker {

    /** Fallback model-squeeze threshold when the policy is not configured. */
    private static final double DEFAULT_MODEL_THRESHOLD = 0.85;

    private final AgentConfig agentConfig;
    private final Tokenizer tokenizer;
    private final ConversationManager conversationManager;
    /** Local history pruning (prune band). */
    private final DefaultContextPruner contextPruner;
    /** Model deep compaction (summarize band). */
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
     * Decides the compaction band for this checkpoint: none, summarize, or prune.
     *
     * <p>Summarization is attempted while it is still available and the attempt budget of the
     * window has room; once it is closed, local pruning handles each oversized checkpoint. Its
     * persisted stage controls how history is retained, rather than preventing further passes.</p>
     *
     * @param conversationManager the manager holding the execution's context
     * @param execution            the execution being checked
     * @return the band to run; never {@code null}
     */
    public ContextSqueezeRequest shouldSqueezeContext(ConversationManager conversationManager, Execution execution) {
        double ratio = tokenRatio(execution);
        if (ratio < modelThreshold()) {
            return ContextSqueezeRequest.none(); // under the threshold: back in the safe zone
        }
        CompactionState state = conversationManager.compactionState(execution);
        boolean summarizeAvailable = !state.summaryUnavailable()
                && state.summaryAttempts() < maxSummaryAttempts()
                && modelCompacter != null;
        if (summarizeAvailable) {
            return ContextSqueezeRequest.summarize();
        }
        return ContextSqueezeRequest.prune();
    }

    /**
     * Runs the band this checkpoint decided on.
     *
     * <p>A summarize attempt that leaves summarization closed — because it failed, or because it
     * spent the last attempt without getting under the threshold — hands the checkpoint over to the
     * prune band immediately: waiting for the next checkpoint would leave the oversized context in
     * place for another full model round.</p>
     */
    private void compactIfNeeded(Execution execution) {
        ContextSqueezeRequest request = shouldSqueezeContext(conversationManager, execution);
        switch (request.band()) {
            case NONE -> {
                return;
            }
            case SUMMARIZE -> {
                while (compactBySummary(execution, request)) {
                    if (tokenizer.count(conversationManager.messages(execution)) <= agentConfig.maxTokens()) {
                        return;
                    }
                }

                compactByPrune(execution, request);
            }
            case PRUNE -> compactByPrune(execution, request);
        }
    }

    /**
     * Runs the summarize band under the attempt gate.
     *
     * <p>Every exit path leaves the execution's compaction state consistent: exactly one attempt is
     * counted before the model call, a rebuild that brings the context back under the threshold
     * opens a new trigger window, and any failure closes summarization for the rest of the
     * execution so no further checkpoint pays for the same failing call.</p>
     *
     * @return {@code true} when the checkpoint is done; {@code false} when summarization just became
     *         unavailable and the prune band should take over in this same checkpoint
     */
    private boolean compactBySummary(Execution execution, ContextSqueezeRequest request) {
        CompactionState state = conversationManager.compactionState(execution);
        if (state.summaryUnavailable()) {
            log.info("[context-compact] summarization is closed for this execution, model compaction "
                    + "skipped: executionId={}", execution.getId());
            return false;
        }
        int attemptBudget = maxSummaryAttempts();
        if (state.summaryAttempts() >= attemptBudget) {
            markSummarizationUnavailable(execution, "the summarize attempt budget (" + attemptBudget
                    + ") is spent and the context is still above the model threshold");
            return false;
        }
        if (modelCompacter == null) {
            log.warn("[context-compact] no model compacter is wired, the prune band takes over: executionId={}",
                    execution.getId());
            return false;
        }

        // Counted before the model call, so a call that throws is still an attempt.
        execution.setCompactionState(state.beforeSummaryAttempt());
        boolean compacted = false;
        try {
            compacted = modelCompacter.compact(new ContextCompactRequest(execution, request));
        } catch (RuntimeException e) {
            // The compacter reports its own failures, but an escaping one must still close the gate.
            log.warn("[context-compact] model compaction raised, summarization will be closed: executionId={}",
                    execution.getId(), e);
        }
        if (!compacted) {
            markSummarizationUnavailable(execution, "the summarize call failed or returned no usable summary");
            return false;
        }

        // Re-read the state: the rebuild just advanced the summary generation, and that write must
        // not be lost by overwriting it with the pre-call snapshot.
        CompactionState afterRebuild = conversationManager.compactionState(execution);
        double ratio = tokenRatio(execution);
        if (ratio < modelThreshold()) {
            execution.setCompactionState(afterRebuild.summaryWindowReset());
            log.info("[context-compact] model compaction brought the context back under the threshold, "
                    + "attempts reset: ratio={}, attempts={}, executionId={}",
                    ratio, afterRebuild.summaryAttempts(), execution.getId());
            return true;
        }
        if (afterRebuild.summaryAttempts() >= attemptBudget) {
            markSummarizationUnavailable(execution, "the summarize attempt budget (" + attemptBudget
                    + ") is spent and the rebuilt context is still at ratio=" + ratio);
            return false;
        }
        log.info("[context-compact] chained summary accepted, the next checkpoint compresses this "
                        + "summary again: ratio={}, attempts={}/{}, executionId={}",
                ratio, afterRebuild.summaryAttempts(), attemptBudget, execution.getId());
        return true;
    }

    /**
     * Runs local pruning and lets an irreducible input-budget error terminate the execution.
     *
     * <p>The pruner validates the complete candidate before committing it. Swallowing that failure
     * would send an oversized context to the main model.</p>
     */
    private void compactByPrune(Execution execution, ContextSqueezeRequest request) {
        CompactionState state = conversationManager.compactionState(execution);

        if (!state.summaryUnavailable()) {
            markSummarizationUnavailable(execution, "no compact model is configured or its attempt budget is exhausted");
        }

        if (contextPruner == null) {
            throw new IllegalStateException("Context pruning is required but no context pruner is configured");
        }

        contextPruner.compact(new ContextCompactRequest(execution, request));
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
        log.warn("[context-compact] summarization closed for the rest of this execution ({}); later "
                + "checkpoints will not call the compact model: executionId={}", reason, execution.getId());
    }

    /** Token ratio of the current context against the configured cap. */
    private double tokenRatio(Execution execution) {
        return tokenizer.calcCurrentTokenRatio(conversationManager.messages(execution), agentConfig.maxTokens());
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
