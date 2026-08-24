package com.summit.harness.springbootautoconfigure.properties;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;

import java.time.Duration;

/** 上下文压缩专用模型配置：{@code lingxi.agent.model.conf.compact}，默认无 thinking。 */
@Data
@ConfigurationProperties(prefix = "lingxi.agent.model.conf.compact")
public class CompactContextModelProperties {

    /** Provider 名称，可指向自定义 Provider 的 name */
    private String provider = "default-compact";

    private String baseUrl;
    private String apiKey;
    private String modelName;
    private int maxTokens = 32768;
    /** none | minimal | low | medium | high | xhigh | max */
    private String reasoningEffort = "none";
    private boolean returnThinking = false;
    private boolean sendThinking = false;
    private Duration timeout = Duration.ofSeconds(60);
}
