package com.summit.core.conf;

import lombok.Builder;
import lombok.Data;
import lombok.extern.jackson.Jacksonized;

import java.time.Duration;
import java.util.Map;

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
    @Builder(toBuilder = true)
    @Jacksonized
    public record AnthropicOptions(
            /*
              Optional Anthropic thinking mode; inferred from the model name and effort when absent.
              */
            String thinkingType,
            /* Optional token budget for Anthropic manual thinking. */
            Integer thinkingBudgetTokens,
            /*
              Whether Anthropic may cache the system prompt and tool definitions across requests.

              <p>Anthropic reuses a prompt prefix only when the request marks it with {@code cache_control};
              without that marker {@code cache_read_input_tokens} stays zero however often the prefix repeats.
              A cache write costs 1.25x and a read 0.1x, so the marker pays off from the second request that
              shares the prefix.</p>
             */
            boolean promptCache,
            /*
              How long Anthropic keeps a cached prefix: {@code 5m} (default) or {@code 1h}. Applies to every
              breakpoint; a cache hit refreshes it, so {@code 1h} is only worth its higher write price when
              requests sharing the prefix are more than five minutes apart.
             */
            String promptCacheTtl,
            /*
              Whether Anthropic may cache the whole conversation and move the breakpoint forward as it grows.

              <p>An agent loop re-sends an ever longer prefix, so this reads the earlier rounds back from the
              cache instead of reprocessing them. It only pays off while the head of the conversation stays
              byte-identical: a request whose beginning changes every round pays the write price and reads
              nothing, which costs more than no caching at all.</p>
             */
            boolean promptCacheConversation
    ) {
    }

    @Builder(toBuilder = true)
    @Jacksonized
    public record ImageOptions(
            String quality,
            String size,
            Map<String, String> customHeaders
    ) {
        public ImageOptions {
            customHeaders = customHeaders == null ? Map.of() : Map.copyOf(customHeaders);
        }
    }

    private String baseUrl;
    private String apiKey;
    private String modelName;
    private String provider;
    private Duration timeout;
    private Integer maxTokens;
    private String reasoningEffort;
    private boolean returnThinking;
    private boolean sendThinking;
    @Builder.Default
    private int maxRetries = 3;
    private AnthropicOptions anthropicOptions;
    private ImageOptions imageOptions;

}
