package com.summit.harness.springbootautoconfigure.properties;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;

import java.time.Duration;

@Data
@ConfigurationProperties(prefix = "lingxi.agent.model.conf.stream")
public class StreamingAgentProperties {
    /** Provider 名称，默认使用内置流式模型 Provider，可指向自定义 Provider */
    private String provider = "default-streaming";
    private String baseUrl;
    private  String apiKey;
    private String modelName;
    private double squeezeThreshold = 0.85;
    private int maxTokens = 102400;
    /**
     *  `none`, `minimal`, `low`, `medium`, `high`, `xhigh`, `max` a
     */
    private String reasoningEffort = "low";
    private boolean returnThinking = true;
    private boolean sendThinking = true;
    private Duration timeout = Duration.ofSeconds(60);

}
