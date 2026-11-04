package com.summit.harness.springbootautoconfigure.properties.agent;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "lingxi.agent.runtime.image-window")
public record AgentImageWindowProperties(Integer maxImages, Integer retainImages) {
}
