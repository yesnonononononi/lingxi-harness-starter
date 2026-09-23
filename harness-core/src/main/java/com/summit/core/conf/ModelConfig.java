package com.summit.core.conf;

import lombok.Builder;
import lombok.Data;
import lombok.NonNull;

import java.time.Duration;

@Data
@Builder
public class ModelConfig {
    private @NonNull String baseUrl;
    private @NonNull String apiKey;
    private @NonNull String modelName;
    private  String provider;
    private Duration timeout;
    private Integer maxTokens;
    private String reasoningEffort;
    private boolean returnThinking;
    private boolean sendThinking;
}
