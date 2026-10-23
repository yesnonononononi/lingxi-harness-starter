package com.summit.adapter.langchain4j.model.provider;

import com.summit.adapter.langchain4j.model.ChatModelAdapter;
import com.summit.core.conf.ModelConfig;
import com.summit.core.model.DefaultModelProviderNames;
import com.summit.core.model.chat.ChatModelProvider;
import dev.langchain4j.http.client.HttpClientBuilderFactory;
import dev.langchain4j.http.client.jdk.JdkHttpClient;
import dev.langchain4j.model.openai.OpenAiChatModel;
import java.util.Objects;

/** OpenAI-protocol reasoning model provider, provider name {@code default}. */
public class OpenAiChatModelProvider implements ChatModelProvider {
    private final HttpClientBuilderFactory httpClientBuilderFactory;

    public OpenAiChatModelProvider() {
        this(JdkHttpClient::builder);
    }

    /** Each model needs a fresh mutable builder; client pools may still be shared by the factory. */
    public OpenAiChatModelProvider(HttpClientBuilderFactory httpClientBuilderFactory) {
        this.httpClientBuilderFactory = Objects.requireNonNull(httpClientBuilderFactory, "httpClientBuilderFactory");
    }

    @Override
    public String name() {
        return DefaultModelProviderNames.DEFAULT;
    }

    @Override
    public ChatModelAdapter create(ModelConfig config) {
        return new ChatModelAdapter(OpenAiChatModel.builder()
                .httpClientBuilder(Objects.requireNonNull(httpClientBuilderFactory.create(), "HTTP client factory returned null"))
                .baseUrl(config.getBaseUrl())
                .apiKey(config.getApiKey())
                .sendThinking(config.isSendThinking())
                .returnThinking(config.isReturnThinking())
                .maxTokens(config.getMaxTokens())
                .reasoningEffort(config.getReasoningEffort())
                .modelName(config.getModelName())
                .timeout(config.getTimeout())
                .maxRetries(config.getMaxRetries())
                .build());
    }
}
