package com.summit.runtime.agent;

import lombok.Builder;

@Builder
public record AgentConfig(Double squeezeThreshold, Integer maxTokens, Integer maxIterations,String systemPrompt) {
}
