package com.summit.core.conf;

import lombok.Builder;
import lombok.Data;
import lombok.extern.jackson.Jacksonized;

import java.time.Duration;

/**
 * One model selection: which provider, which endpoint, which model name.
 *
 * <p>Endpoint fields are optional so a deployment can keep credentials out of its configuration files
 * and let each request carry its own {@code ModelConfig}. Anything that actually builds a model has to
 * tolerate them being absent; the provider name still has to resolve to a registered provider.</p>
 */
@Data
@Builder(toBuilder = true)
@Jacksonized
public class ModelConfig {
    private String baseUrl;
    private String apiKey;
    private String modelName;
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
