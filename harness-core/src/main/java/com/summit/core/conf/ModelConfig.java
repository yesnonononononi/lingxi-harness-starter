package com.summit.core.conf;

import lombok.Builder;
import lombok.Data;
import lombok.NonNull;
import lombok.extern.jackson.Jacksonized;

import java.time.Duration;

@Data
@Builder(toBuilder = true)
@Jacksonized
public class ModelConfig {
    private @NonNull String baseUrl;
    private @NonNull String apiKey;
    private @NonNull String modelName;
    private  String provider;
    private Duration timeout;
    private Integer maxTokens;
    private String reasoningEffort;
    /** Optional Anthropic thinking mode; inferred from the model name and effort when absent. */
    private String thinkingType;
    /** Optional token budget for Anthropic manual thinking. */
    private Integer thinkingBudgetTokens;
    private boolean returnThinking;
    private boolean sendThinking;
}
