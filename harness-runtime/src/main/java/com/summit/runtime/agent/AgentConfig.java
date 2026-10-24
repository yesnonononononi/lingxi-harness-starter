package com.summit.runtime.agent;

import lombok.Builder;

/**
 * Runtime budgets and compaction policy of one agent.
 *
 * @param squeezeThreshold            the progressive squeeze policy that decides the compaction band
 * @param maxTokens                   context cap the token ratio is measured against
 * @param maxIterations               loop iteration budget of one execution
 * @param maxConsecutiveCompactions   consecutive compacting rounds tolerated before the loop stops
 * @param maxSummaryAttempts          total summarize attempts of one trigger window: the first
 *                                    attempt plus its chained re-compactions. {@code null} means a
 *                                    single attempt — the framework deliberately carries no default
 *                                    constant, so "how many times may I pay for a summary" stays an
 *                                    application decision. Values {@code <= 0} are rejected at
 *                                    construction time.
 */
@Builder
public record AgentConfig(
        ProgressiveSqueezePolicy squeezeThreshold,
        Integer maxTokens,
        Integer maxIterations,
        Integer maxConsecutiveCompactions,
        Integer maxSummaryAttempts
) {
    /** Consecutive compaction rounds tolerated when the application configures no limit. */
    public static final int DEFAULT_MAX_CONSECUTIVE_COMPACTIONS = 3;

    public AgentConfig {
        maxTokens = maxTokens == null ? 1_024_000 : maxTokens;
        maxIterations = maxIterations == null ? 500 : maxIterations;
        maxConsecutiveCompactions = maxConsecutiveCompactions == null
                ? DEFAULT_MAX_CONSECUTIVE_COMPACTIONS : maxConsecutiveCompactions;
        if (maxTokens <= 0 || maxIterations <= 0 || maxConsecutiveCompactions <= 0) {
            throw new IllegalArgumentException("Runtime budgets must be positive");
        }
        // Rejected at startup rather than clamped: a zero/negative budget would mean "summarize
        // never", which is a policy the caller must express explicitly, not by an off-by-one value.
        if (maxSummaryAttempts != null && maxSummaryAttempts <= 0) {
            throw new IllegalArgumentException("maxSummaryAttempts must be positive when configured: "
                    + maxSummaryAttempts);
        }
    }

    /**
     * The summarize attempt budget actually applied: {@link #maxSummaryAttempts()} when configured,
     * a single attempt otherwise.
     *
     * @return a positive attempt budget
     */
    public int summaryAttemptBudget() {
        return maxSummaryAttempts == null ? 1 : maxSummaryAttempts;
    }

    public record ProgressiveSqueezePolicy(
            OriginalSqueeze truncateSqueeze,
            ModelSqueeze modelSqueeze
    ){}

    public record OriginalSqueeze(Double threshold,int expectTruncateTurn){
        public OriginalSqueeze defaultPolicy(){
            return new OriginalSqueeze(0.7,5);
        }
    }
    public record ModelSqueeze(Double threshold){
        public ModelSqueeze defaultPolicy(){
            return new ModelSqueeze(0.85);
        }
    }
}
