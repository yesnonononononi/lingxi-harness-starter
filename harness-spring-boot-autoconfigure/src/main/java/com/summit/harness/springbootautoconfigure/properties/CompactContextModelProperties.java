package com.summit.harness.springbootautoconfigure.properties;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;

import java.time.Duration;

/** Model properties for context compaction: {@code lingxi.agent.model.conf.compact}. */
@Data
@ConfigurationProperties(prefix = "lingxi.agent.model.conf.compact")
public class CompactContextModelProperties {

    /** Provider name; may point at a custom provider. */
    private String provider;

    private String baseUrl;
    private String apiKey;
    private String modelName;
    private int maxTokens = 32768;
    /** none | minimal | low | medium | high | xhigh | max */
    private String reasoningEffort = "none";
    private boolean returnThinking = false;
    private boolean sendThinking = false;
    private Duration timeout = Duration.ofSeconds(60);

    /** Whether the application selected a dedicated compaction model. */
    public boolean isModelConfigured() {
        return hasText(provider) || hasText(baseUrl) || hasText(apiKey) || hasText(modelName);
    }

    private static boolean hasText(String value) {
        return value != null && !value.isBlank();
    }
}
