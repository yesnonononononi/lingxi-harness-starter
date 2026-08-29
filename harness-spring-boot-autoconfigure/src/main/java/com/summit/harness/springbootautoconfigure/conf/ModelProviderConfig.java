package com.summit.harness.springbootautoconfigure.conf;

import com.summit.harness.springbootautoconfigure.properties.agent.AgentChatProperties;
import com.summit.harness.springbootautoconfigure.properties.CompactContextModelProperties;
import com.summit.harnesscore.model.ModelProvider;
import com.summit.harnesscore.model.ModelProviderRegistry;
import com.summit.adapter.langchain4j.provider.OpenAiChatModelProvider;
import com.summit.adapter.langchain4j.provider.OpenAiCompactContextModelProvider;
import com.summit.adapter.langchain4j.provider.OpenAiStreamingModelProvider;
import com.summit.harnesscore.model.ChatModel;
import com.summit.harnesscore.model.StreamingChatModel;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;

import java.util.List;

/** Registers the three model providers: default (reasoning), default-streaming (streaming), default-compact (context compaction). Custom providers registered as beans are collected by the registry automatically. */
@AutoConfiguration
@EnableConfigurationProperties({AgentChatProperties.class, CompactContextModelProperties.class})
public class ModelProviderConfig {

    @Bean
    public ModelProvider<ChatModel> chatModelModelProvider() {
        return new OpenAiChatModelProvider();
    }

    @Bean
    public ModelProvider<StreamingChatModel> streamingChatModelModelProvider() {
        return new OpenAiStreamingModelProvider();
    }

    @Bean(name = "compactContextChatModelProvider")
    public ModelProvider<ChatModel> compactContextChatModelProvider() {
        return new OpenAiCompactContextModelProvider();
    }

    @Bean
    public ModelProviderRegistry<StreamingChatModel> streamingModelProviderRegistry(List<ModelProvider<StreamingChatModel>> providers) {
        ModelProviderRegistry<StreamingChatModel> registry = new ModelProviderRegistry<>();
        providers.forEach(registry::register);
        return registry;
    }

    @Bean
    public ModelProviderRegistry<ChatModel> chatModelProviderRegistry(List<ModelProvider<ChatModel>> providers) {
        ModelProviderRegistry<ChatModel> registry = new ModelProviderRegistry<>();
        providers.forEach(registry::register);
        return registry;
    }
}
