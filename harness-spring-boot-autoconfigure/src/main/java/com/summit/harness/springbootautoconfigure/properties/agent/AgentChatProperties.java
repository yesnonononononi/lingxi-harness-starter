package com.summit.harness.springbootautoconfigure.properties.agent;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;

import java.time.Duration;

@Data
@ConfigurationProperties(prefix = "lingxi.agent.model.conf.chat")
public class AgentChatProperties {
    private String baseUrl;
    private  String apiKey;
    private String modelName;
    private double squeezeThreshold = 0.85;
    private Integer maxIterations = 50;
    private int maxTokens = 102400;
    // `none`, `minimal`, `low`, `medium`, `high`, `xhigh`, `max` a
    private String reasoningEffort = "low";
    private boolean returnThinking = true;
    private boolean sendThinking = true;
    private Duration timeout = Duration.ofSeconds(60);

}
