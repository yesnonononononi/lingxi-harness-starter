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
    /**
     * Whether Anthropic may cache the system prompt and tool definitions across requests.
     *
     * <p>Anthropic reuses a prompt prefix only when the request marks it with {@code cache_control};
     * without that marker {@code cache_read_input_tokens} stays zero however often the prefix repeats.
     * A cache write costs 1.25x and a read 0.1x, so the marker pays off from the second request that
     * shares the prefix.</p>
     */
    private boolean promptCache;
}
