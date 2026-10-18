package com.summit.harness.springbootautoconfigure.config;

import com.summit.adapter.langchain4j.model.provider.*;
import com.summit.core.model.ModelProvider;
import com.summit.core.model.ModelProviderRegistry;
import com.summit.core.model.chat.ChatModel;
import com.summit.core.model.streaming.StreamingChatModel;
import com.summit.harness.springbootautoconfigure.properties.CompactContextModelProperties;
import com.summit.harness.springbootautoconfigure.properties.agent.AgentChatProperties;
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
    @ConditionalOnMissingBean(name = "chatModelModelProvider")
    public ModelProvider<ChatModel> chatModelModelProvider() {
        return new OpenAiChatModelProvider();
    }

    @Bean
    @ConditionalOnMissingBean(name = "streamingChatModelModelProvider")
    public ModelProvider<StreamingChatModel> streamingChatModelModelProvider() {
        return new OpenAiStreamingModelProvider();
    }

    @Bean(name = "compactContextChatModelProvider")
    @ConditionalOnMissingBean(name = "compactContextChatModelProvider")
    public ModelProvider<ChatModel> compactContextChatModelProvider() {
        return new OpenAiCompactContextModelProvider();
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
    public ModelProvider<ChatModel> anthropicChatModelProvider() {
        return new AnthropicChatModelProvider();
    }

    @Bean
    @ConditionalOnMissingBean(name = "anthropicStreamingModelProvider")
    public ModelProvider<StreamingChatModel> anthropicStreamingModelProvider() {
        return new AnthropicStreamingModelProvider();
    }

    @Bean
    @ConditionalOnMissingBean(name = "anthropicCompactContextModelProvider")
    public ModelProvider<ChatModel> anthropicCompactContextModelProvider() {
        return new AnthropicCompactContextModelProvider();
    }

    @Bean
    @ConditionalOnMissingBean(name = "chatModelProviderRegistry")
    public ModelProviderRegistry<ChatModel> chatModelProviderRegistry(List<ModelProvider<ChatModel>> providers) {
        ModelProviderRegistry<ChatModel> registry = new ModelProviderRegistry<>();
        providers.forEach(registry::register);
        return registry;
    }
}
