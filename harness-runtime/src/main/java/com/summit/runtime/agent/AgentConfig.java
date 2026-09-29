package com.summit.runtime.agent;

import lombok.Builder;

@Builder
public record AgentConfig(
        ProgressiveSqueezePolicy squeezeThreshold,
        Integer maxTokens,
        Integer maxIterations,
        Integer maxConsecutiveCompactions
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
