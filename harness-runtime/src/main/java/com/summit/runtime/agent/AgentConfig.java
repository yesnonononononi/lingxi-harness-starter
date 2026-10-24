package com.summit.runtime.agent;

import lombok.Builder;

/** Runtime input budget, loop limits and model-summary policy. */
@Builder
public record AgentConfig(
        ProgressiveSqueezePolicy squeezeThreshold,
        Integer maxTokens,
        Integer maxIterations,
        Integer maxConsecutiveCompactions,
        Integer maxSummaryAttempts
) {
    public static final int DEFAULT_MAX_CONSECUTIVE_COMPACTIONS = 3;

    public AgentConfig {
        maxTokens = maxTokens == null ? 1_024_000 : maxTokens;
        maxIterations = maxIterations == null ? 500 : maxIterations;
        maxConsecutiveCompactions = maxConsecutiveCompactions == null
                ? DEFAULT_MAX_CONSECUTIVE_COMPACTIONS : maxConsecutiveCompactions;

        if (maxTokens <= 0 || maxIterations <= 0 || maxConsecutiveCompactions <= 0) {
            throw new IllegalArgumentException("Runtime budgets must be positive");
        }

        if (maxSummaryAttempts != null && maxSummaryAttempts <= 0) {
            throw new IllegalArgumentException("maxSummaryAttempts must be positive when configured: "
                    + maxSummaryAttempts);
        }
    }

    /** Null deliberately means one attempt; applications choose whether another attempt is allowed. */
    public int summaryAttemptBudget() {
        return maxSummaryAttempts == null ? 1 : maxSummaryAttempts;
    }

    /** The trigger is separate from the hard input budget checked after local pruning. */
    public record ProgressiveSqueezePolicy(ModelSqueeze modelSqueeze) {
    }

    public record ModelSqueeze(Double threshold) {
        public ModelSqueeze defaultPolicy() {
            return new ModelSqueeze(0.85);
        }
    }

    /** Published by the properties owner without introducing an upward module dependency. */
    public record CompactionTuning(Integer maxSummaryAttempts) {
    }
}
