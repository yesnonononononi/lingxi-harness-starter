package com.summit.harness.springbootautoconfigure.config;

import com.summit.adapter.langchain4j.model.provider.*;
import com.summit.core.model.ModelProvider;
import com.summit.core.model.ModelProviderRegistry;
import com.summit.core.model.chat.ChatModel;
import com.summit.core.model.image.ImageModel;
import com.summit.core.model.streaming.StreamingChatModel;
import com.summit.harness.springbootautoconfigure.properties.CompactContextModelProperties;
import com.summit.harness.springbootautoconfigure.properties.agent.AgentChatProperties;
import dev.langchain4j.http.client.HttpClientBuilderFactory;
import dev.langchain4j.http.client.jdk.JdkHttpClient;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;

import java.util.List;

/**
 * Registers OpenAI and Anthropic chat, streaming and compaction providers. Custom providers registered as beans are collected by the
 * registry automatically.
 *
 * <p>The providers are gated by bean <em>name</em>, not by type. They are contributed into collected
 * lists ({@code List<ModelProvider<ChatModel>>}), and the two chat providers share that list: a
 * type-level condition would let an application provider for one channel suppress the built-in of
 * the other. Named gating keeps each default independently replaceable.</p>
 */
@AutoConfiguration
@EnableConfigurationProperties({AgentChatProperties.class, CompactContextModelProperties.class})
public class ModelProviderConfig {

    @Bean
    @ConditionalOnMissingBean(HttpClientBuilderFactory.class)
    public HttpClientBuilderFactory httpClientBuilderFactory() {
        return JdkHttpClient::builder;
    }

    @Bean
    @ConditionalOnMissingBean(name = "chatModelModelProvider")
    public ModelProvider<ChatModel> chatModelModelProvider(HttpClientBuilderFactory httpClientBuilderFactory) {
        return new OpenAiChatModelProvider(httpClientBuilderFactory);
    }

    @Bean
    @ConditionalOnMissingBean(name = "streamingChatModelModelProvider")
    public ModelProvider<StreamingChatModel> streamingChatModelModelProvider(HttpClientBuilderFactory httpClientBuilderFactory) {
        return new OpenAiStreamingModelProvider(httpClientBuilderFactory);
    }

    @Bean(name = "compactContextChatModelProvider")
    @ConditionalOnMissingBean(name = "compactContextChatModelProvider")
    public ModelProvider<ChatModel> compactContextChatModelProvider(HttpClientBuilderFactory httpClientBuilderFactory) {
        return new OpenAiCompactContextModelProvider(httpClientBuilderFactory);
    }

    @Bean
    @ConditionalOnMissingBean(name = "streamingModelProviderRegistry")
    public ModelProviderRegistry<StreamingChatModel> streamingModelProviderRegistry(List<ModelProvider<StreamingChatModel>> providers) {
        ModelProviderRegistry<StreamingChatModel> registry = new ModelProviderRegistry<>();
        providers.forEach(registry::register);
        return registry;
    }

    @Bean
    @ConditionalOnMissingBean(name = "anthropicChatModelProvider")
    public ModelProvider<ChatModel> anthropicChatModelProvider(HttpClientBuilderFactory httpClientBuilderFactory) {
        return new AnthropicChatModelProvider(httpClientBuilderFactory);
    }
    @Bean
    @ConditionalOnMissingBean(name = "anthropicStreamingModelProvider")
    public ModelProvider<StreamingChatModel> anthropicStreamingModelProvider(HttpClientBuilderFactory httpClientBuilderFactory) {
        return new AnthropicStreamingModelProvider(httpClientBuilderFactory);
    }

    @Bean
    @ConditionalOnMissingBean(name = "anthropicCompactContextModelProvider")
    public ModelProvider<ChatModel> anthropicCompactContextModelProvider(HttpClientBuilderFactory httpClientBuilderFactory) {
        return new AnthropicCompactContextModelProvider(httpClientBuilderFactory);
    }

    @Bean(name = "commonImageModelProvider")
    @ConditionalOnMissingBean(name = "commonImageModelProvider")
    public ModelProvider<ImageModel> commonImageModelProvider(HttpClientBuilderFactory httpClientBuilderFactory) {
        return new CommonImageModelProvider(httpClientBuilderFactory);
    }

    @Bean
    @ConditionalOnMissingBean(name = "imageModelProviderRegistry")
    public ModelProviderRegistry<ImageModel> imageModelProviderRegistry(List<ModelProvider<ImageModel>> providers) {
        ModelProviderRegistry<ImageModel> registry = new ModelProviderRegistry<>();
        providers.forEach(registry::register);
        return registry;
    }
    @Bean
    @ConditionalOnMissingBean(name = "chatModelProviderRegistry")
    public ModelProviderRegistry<ChatModel> chatModelProviderRegistry(List<ModelProvider<ChatModel>> providers) {
        ModelProviderRegistry<ChatModel> registry = new ModelProviderRegistry<>();
        providers.forEach(registry::register);
        return registry;
    }
}
