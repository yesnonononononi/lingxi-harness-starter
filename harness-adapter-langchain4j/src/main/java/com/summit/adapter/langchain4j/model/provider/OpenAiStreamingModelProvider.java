package com.summit.adapter.langchain4j.model.provider;

import com.summit.adapter.langchain4j.model.StreamingChatModelAdapter;
import com.summit.core.conf.ModelConfig;
import com.summit.core.model.DefaultModelProviderNames;
import com.summit.core.model.streaming.StreamingChatModelProvider;
import dev.langchain4j.http.client.HttpClientBuilderFactory;
import dev.langchain4j.http.client.jdk.JdkHttpClient;
import dev.langchain4j.model.openai.OpenAiStreamingChatModel;
import java.util.Objects;

/** OpenAI-protocol streaming model provider, provider name {@code default-streaming}. */
public class OpenAiStreamingModelProvider implements StreamingChatModelProvider {
    private final HttpClientBuilderFactory httpClientBuilderFactory;

    public OpenAiStreamingModelProvider() {
        this(JdkHttpClient::builder);
    }

    /** Each model needs a fresh mutable builder; client pools may still be shared by the factory. */
    public OpenAiStreamingModelProvider(HttpClientBuilderFactory httpClientBuilderFactory) {
        this.httpClientBuilderFactory = Objects.requireNonNull(httpClientBuilderFactory, "httpClientBuilderFactory");
    }

    @Override
    public String name() {
        return DefaultModelProviderNames.DEFAULT_STREAMING;
    }

    @Override
    public StreamingChatModelAdapter create(ModelConfig config) {
        return new StreamingChatModelAdapter(OpenAiStreamingChatModel.builder()
                .httpClientBuilder(Objects.requireNonNull(httpClientBuilderFactory.create(), "HTTP client factory returned null"))
                .baseUrl(config.getBaseUrl())
                .apiKey(config.getApiKey())
                .sendThinking(config.isSendThinking())
                .returnThinking(config.isReturnThinking())
                .maxTokens(config.getMaxTokens())
                .reasoningEffort(config.getReasoningEffort())
                .modelName(config.getModelName())
                .timeout(config.getTimeout())
                .build());
    }
}
