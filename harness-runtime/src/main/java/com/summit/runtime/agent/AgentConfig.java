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
 * @param pruneTargetRatio            share of {@link #maxTokens} the pruned context is sized
 *                                    against. Only an initial value until a real-session load test
 *                                    calibrates it; pruning never gives up because it cannot reach
 *                                    it, it degrades as far as it can and reports.
 * @param fullSegmentCount            how many of the newest segments may stay {@code FULL}
 * @param fullToolOutputCap           token cap applied to a single tool result kept in a
 *                                    {@code FULL} segment
 */
@Builder
public record AgentConfig(
        ProgressiveSqueezePolicy squeezeThreshold,
        Integer maxTokens,
        Integer maxIterations,
        Integer maxConsecutiveCompactions,
        Integer maxSummaryAttempts,
        Double pruneTargetRatio,
        Integer fullSegmentCount,
        Integer fullToolOutputCap
) {
    /** Consecutive compaction rounds tolerated when the application configures no limit. */
    public static final int DEFAULT_MAX_CONSECUTIVE_COMPACTIONS = 3;
    /** Initial prune target ratio; calibrated by a load test before it is treated as tuned. */
    public static final double DEFAULT_PRUNE_TARGET_RATIO = 0.40;
    /** Newest segments that may stay {@code FULL} when no count is configured. */
    public static final int DEFAULT_FULL_SEGMENT_COUNT = 8;
    /** Token cap of a single tool result inside a {@code FULL} segment when unconfigured. */
    public static final int DEFAULT_FULL_TOOL_OUTPUT_CAP = 4_000;

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
        pruneTargetRatio = pruneTargetRatio == null ? DEFAULT_PRUNE_TARGET_RATIO : pruneTargetRatio;
        if (!(pruneTargetRatio > 0d) || pruneTargetRatio > 1d) {
            throw new IllegalArgumentException("pruneTargetRatio must be in (0, 1]: " + pruneTargetRatio);
        }
        fullSegmentCount = fullSegmentCount == null ? DEFAULT_FULL_SEGMENT_COUNT : fullSegmentCount;
        if (fullSegmentCount < 0) {
            throw new IllegalArgumentException("fullSegmentCount must not be negative: " + fullSegmentCount);
        }
        fullToolOutputCap = fullToolOutputCap == null ? DEFAULT_FULL_TOOL_OUTPUT_CAP : fullToolOutputCap;
        if (fullToolOutputCap <= 0) {
            throw new IllegalArgumentException("fullToolOutputCap must be positive: " + fullToolOutputCap);
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

    /**
     * The token budget a prune pass sizes the context against.
     *
     * @return {@code pruneTargetRatio * maxTokens}, rounded down, at least one token
     */
    public int pruneBudgetTokens() {
        return Math.max(1, (int) Math.floor(pruneTargetRatio * maxTokens));
    }

    /**
     * The compaction band policy: the ratio at which the model summarize band is entered.
     *
     * <p>It used to describe two bands — a local truncation band {@code [truncateThreshold,
     * modelThreshold)} and the model band above it. The truncation band is gone, and with it the
     * truncation threshold and the per-pass round count, so the policy now carries exactly one
     * threshold. Pruning is not ratio-driven: it is the fallback that runs once summarization is
     * closed, and its budget comes from {@link #pruneTargetRatio()}.</p>
     */
    public record ProgressiveSqueezePolicy(ModelSqueeze modelSqueeze){}

    public record ModelSqueeze(Double threshold){
        public ModelSqueeze defaultPolicy(){
            return new ModelSqueeze(0.85);
        }
    }

    /**
     * Compaction tuning published by the module that owns the compaction properties.
     *
     * <p>It travels as its own bean because the module that knows the properties (the kernel tools)
     * must not be a dependency of the module that builds {@link AgentConfig} (the auto
     * configuration): the arrow would point upwards. The auto configuration consumes this through an
     * {@code ObjectProvider} exactly like the squeeze policy, so a deployment without the kernel
     * tools simply gets the {@link AgentConfig} defaults.</p>
     *
     * @param maxSummaryAttempts total summarize attempts of one trigger window, {@code null} = single
     * @param pruneTargetRatio   share of the context cap a prune pass sizes against, {@code null} = default
     * @param fullSegmentCount   newest segments a prune pass may keep {@code FULL}, {@code null} = default
     * @param fullToolOutputCap  token cap of one tool result inside a {@code FULL} segment, {@code null} = default
     */
    public record CompactionTuning(
            Integer maxSummaryAttempts,
            Double pruneTargetRatio,
            Integer fullSegmentCount,
            Integer fullToolOutputCap
    ) {}
}
