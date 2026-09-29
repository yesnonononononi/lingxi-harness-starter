package com.summit.runtime.loop;

import com.summit.core.agent.Execution;
import com.summit.core.compact.ContextCompacter;
import com.summit.core.compact.ContextCompactRequest;
import com.summit.core.compact.ContextSqueezeRequest;
import com.summit.core.compact.Tokenizer;
import com.summit.core.conversation.ConversationManager;
import com.summit.core.exception.MaxStepsExceededException;
import com.summit.core.exception.TokenBudgetExceededException;
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
 */
@RequiredArgsConstructor
@Slf4j
public class  BoundaryChecker implements RuntimeBoundaryChecker {

    /** Fallback truncation threshold when the policy is not configured (matches AgentConfig.OriginalSqueeze#defaultPolicy). */
    private static final double DEFAULT_TRUNCATE_THRESHOLD = 0.7;
    /** Fallback rounds per local truncation pass when the policy is not configured (matches AgentConfig.OriginalSqueeze#defaultPolicy). */
    private static final int DEFAULT_TRUNCATE_TURN = 5;
    /** Fallback model-squeeze threshold when the policy is not configured (matches AgentConfig.ModelSqueeze#defaultPolicy). */
    private static final double DEFAULT_MODEL_THRESHOLD = 0.85;

    private final AgentConfig agentConfig;
    private final Tokenizer tokenizer;
    private final ConversationManager conversationManager;
    /** Manual per-round truncation compaction (shouldSqueeze band). */
    private final DefaultManualCompacter manualCompacter;
    /** Model deep compaction (expectAdvanceSqueeze band). */
    private final DefaultModelCompacter modelCompacter;


    @Override
    public CheckPointResult before(Execution execution) {
        if (execution.getModelAttempts() >= effectiveMaxSteps(execution)) {
            throw new MaxStepsExceededException("loop exceeded maximum steps");
        }
        compactIfNeeded(execution);
        return shouldContinue(execution);
    }

    @Override
    public CheckPointResult after(Execution execution) {
        compactIfNeeded(execution);
        return shouldContinue(execution) ;
    }
    /**
     * Progressive squeeze decision based on {@link AgentConfig.ProgressiveSqueezePolicy}:
     * <ul>
     *   <li>ratio in [truncateThreshold, modelThreshold): local round-based truncation
     *       ({@code truncateSqueeze}) kicks in — the number of rounds squeezed per pass
     *       comes from {@code OriginalSqueeze.expectTruncateTurn};</li>
     *   <li>ratio &gt;= modelThreshold: local truncation stops and the model-based deep
     *       compaction ({@code DefaultModelCompacter}) is expected, i.e.
     *       {@code expectAdvanceSqueeze=true}.</li>
     * </ul>
     */
    public ContextSqueezeRequest shouldSqueezeContext(ConversationManager conversationManager, Execution execution) {

        double ratio = this.tokenizer.calcCurrentTokenRatio(conversationManager.messages(execution), agentConfig.maxTokens());

        AgentConfig.ProgressiveSqueezePolicy policy = agentConfig.squeezeThreshold();
        Double truncateThreshold = policy == null || policy.truncateSqueeze() == null
                ? null : policy.truncateSqueeze().threshold();
        int truncateTurn = policy == null || policy.truncateSqueeze() == null
                ? 0 : Math.max(policy.truncateSqueeze().expectTruncateTurn(), 0);
        Double modelThreshold = policy == null || policy.modelSqueeze() == null
                ? null : policy.modelSqueeze().threshold();

        double original = truncateThreshold == null ? DEFAULT_TRUNCATE_THRESHOLD : truncateThreshold;
        double advanced = modelThreshold == null ? DEFAULT_MODEL_THRESHOLD : modelThreshold;
        boolean shouldTruncate = ratio >= original && ratio < advanced;
        return ContextSqueezeRequest.builder()
                .shouldSqueeze(shouldTruncate)
                .truncateTurn(shouldTruncate ? (truncateTurn > 0 ? truncateTurn : DEFAULT_TRUNCATE_TURN) : 0)
                .expectAdvanceSqueeze(ratio >= advanced)
                .build();
    }
    /**
     * Performs one blocking compaction for the progressive squeeze band: the manual compacter truncates
     * rounds when {@code shouldSqueeze}, or the model compacter summarizes and rebuilds the session when
     * {@code expectAdvanceSqueeze}. Returns once the compaction is done so the agent loop can start its
     * next round; does nothing when no band is hit.
     */
    private void compactIfNeeded(Execution execution) {
        ContextSqueezeRequest request = shouldSqueezeContext(conversationManager, execution);
        String band;
        ContextCompacter compacter;
        if (request.expectAdvanceSqueeze()) {
            band = "model";
            compacter = modelCompacter;
        } else if (request.shouldSqueeze()) {
            band = "manual";
            compacter = manualCompacter;
        } else {
            return;
        }
        if (compacter == null) {
            log.warn("【context-compact】no {} compacter wired, compression skipped: executionId={}",
                    band, execution.getId());
            return;
        }
        boolean compacted = compacter.compact(new ContextCompactRequest(execution, request));
        log.info("【context-compact】checkpoint triggered {} band, compacted={}, executionId={}",
                band, compacted, execution.getId());
    }



    private CheckPointResult shouldContinue(Execution execution) throws TokenBudgetExceededException,MaxStepsExceededException {
        if( tokenIsExhausted(execution, conversationManager))  throw new TokenBudgetExceededException("token has been exhausted");



        return CheckPointResult.continueWith();
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

    private boolean tokenIsExhausted(Execution execution, ConversationManager conversationManager) {
        Integer maxTokens = agentConfig.maxTokens();
        if (maxTokens == null) return true;
        int currentContextTokens = tokenizer.count(conversationManager.messages(execution));
        return currentContextTokens >= maxTokens;
    }



}
