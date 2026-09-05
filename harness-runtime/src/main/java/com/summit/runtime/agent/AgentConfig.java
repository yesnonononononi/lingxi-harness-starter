package com.summit.runtime.agent;

import lombok.Builder;

@Builder
public record AgentConfig(
        ProgressiveSqueezePolicy squeezeThreshold,
        Integer maxTokens,
        Integer maxIterations,
        String systemPrompt
) {
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
