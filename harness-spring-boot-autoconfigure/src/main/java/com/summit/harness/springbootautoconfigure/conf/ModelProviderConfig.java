package com.summit.harness.springbootautoconfigure.conf;

import com.summit.harness.springbootautoconfigure.properties.agent.AgentChatProperties;
import com.summit.harness.springbootautoconfigure.properties.CompactContextModelProperties;
import com.summit.harnesscore.model.ModelProvider;
import com.summit.harnesscore.model.ModelProviderRegistry;
import com.summit.runtime.provider.DefaultChatModelProvider;
import com.summit.runtime.provider.DefaultCompactContextModelProvider;
import com.summit.runtime.provider.DefaultStreamingModelProvider;
import dev.langchain4j.model.chat.ChatModel;
import dev.langchain4j.model.chat.StreamingChatModel;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;

import java.util.List;

/** 注册三类模型 Provider：default(推理)、default-streaming(流式)、default-compact(上下文压缩)。自定义 Provider 注册为 Bean 后 registry 自动收集。 */
@AutoConfiguration
@EnableConfigurationProperties({AgentChatProperties.class, CompactContextModelProperties.class})
public class ModelProviderConfig {

    @Bean
    public ModelProvider<ChatModel> chatModelModelProvider() {
        return new DefaultChatModelProvider();
    }

    @Bean
    public ModelProvider<StreamingChatModel> streamingChatModelModelProvider() {
        return new DefaultStreamingModelProvider();
    }

    @Bean(name = "compactContextChatModelProvider")
    public ModelProvider<ChatModel> compactContextChatModelProvider() {
        return new DefaultCompactContextModelProvider();
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
