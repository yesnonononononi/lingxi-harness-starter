package com.summit.adapter.langchain4j.model.provider;

import com.summit.adapter.langchain4j.model.ChatModelAdapter;
import com.summit.core.conf.ModelConfig;
import com.summit.core.model.DefaultModelProviderNames;
import com.summit.core.model.compact.CompactContextModelProvider;
import dev.langchain4j.http.client.HttpClientBuilderFactory;
import dev.langchain4j.http.client.jdk.JdkHttpClient;
import dev.langchain4j.model.anthropic.AnthropicChatModel;
import java.util.Objects;

/** Native Anthropic protocol provider. */
public class AnthropicCompactContextModelProvider implements CompactContextModelProvider {
    private final HttpClientBuilderFactory httpClientBuilderFactory;

    public AnthropicCompactContextModelProvider() {
        this(JdkHttpClient::builder);
    }

    /** Each model needs a fresh mutable builder; client pools may still be shared by the factory. */
    public AnthropicCompactContextModelProvider(HttpClientBuilderFactory httpClientBuilderFactory) {
        this.httpClientBuilderFactory = Objects.requireNonNull(httpClientBuilderFactory, "httpClientBuilderFactory");
    }

    @Override
    public String name() { return DefaultModelProviderNames.ANTHROPIC_COMPACT; }

    @Override
    public ChatModelAdapter create(ModelConfig config) {
        AnthropicModelSettings settings = AnthropicModelSettings.resolve(config);
        return new ChatModelAdapter(AnthropicChatModel.builder()
                .httpClientBuilder(Objects.requireNonNull(httpClientBuilderFactory.create(), "HTTP client factory returned null"))
                .baseUrl(config.getBaseUrl())
                .apiKey(config.getApiKey())
                .modelName(config.getModelName())
                .timeout(config.getTimeout())
                .maxRetries(config.getMaxRetries())
                .maxTokens(settings.maxTokens())
                .sendThinking(config.isSendThinking())
                .returnThinking(config.isReturnThinking())
                .thinkingType(settings.thinkingType())
                .thinkingBudgetTokens(settings.budget())
                .customParameters(settings.customParameters())
                .cacheSystemMessages(settings.cachePrefix())
                .cacheTools(settings.cachePrefix())
                .cacheAutomatically(settings.cacheAutomatically())
                .cacheTtl(settings.cacheTtl())
                .build());
    }
}
