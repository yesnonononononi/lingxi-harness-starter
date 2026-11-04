package com.summit.runtime.agent;

import lombok.Builder;

/** Runtime input budget, loop limits and model-summary policy. */
@Builder
public record AgentConfig(
        ProgressiveSqueezePolicy squeezeThreshold,
        Integer maxTokens,
        Integer maxIterations,
        Integer maxConsecutiveCompactions,
        Integer maxSummaryAttempts,
        ImageWindow imageWindow
) {
    public static final int DEFAULT_MAX_CONSECUTIVE_COMPACTIONS = 3;

    public AgentConfig(ProgressiveSqueezePolicy squeezeThreshold, Integer maxTokens, Integer maxIterations,
                       Integer maxConsecutiveCompactions, Integer maxSummaryAttempts) {
        this(squeezeThreshold, maxTokens, maxIterations, maxConsecutiveCompactions, maxSummaryAttempts, null);
    }

    public AgentConfig {
        maxTokens = maxTokens == null ? 1_024_000 : maxTokens;
        maxIterations = maxIterations == null ? 500 : maxIterations;
        maxConsecutiveCompactions = maxConsecutiveCompactions == null
                ? DEFAULT_MAX_CONSECUTIVE_COMPACTIONS : maxConsecutiveCompactions;
        imageWindow = imageWindow == null ? new ImageWindow(null, null) : imageWindow;

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

    /** Both compaction bands must bring the context below this trigger. */
    public double compactionThreshold() {
        Double configured = squeezeThreshold == null || squeezeThreshold.modelSqueeze() == null
                ? null : squeezeThreshold.modelSqueeze().threshold();
        return configured == null ? 0.85 : configured;
    }

    /** Policy shared by model summarization and local pruning. */
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

    /** Keep headroom after trimming so adding one image rarely rewrites the cached prefix again. */
    public record ImageWindow(Integer maxImages, Integer retainImages) {
        public ImageWindow {
            maxImages = maxImages == null ? 15 : maxImages;
            retainImages = retainImages == null ? Math.max(1, maxImages / 2) : retainImages;
            if (maxImages <= 0 || retainImages <= 0 || retainImages > maxImages) {
                throw new IllegalArgumentException("Image window requires 1 <= retainImages <= maxImages");
            }
        }
    }
}
