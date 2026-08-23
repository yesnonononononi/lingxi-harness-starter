package com.summit.harness.springbootautoconfigure.conf;

import com.summit.harness.springbootautoconfigure.properties.AgentChatProperties;
import com.summit.harness.springbootautoconfigure.properties.AgentProperties;
import com.summit.harnesscore.model.ModelProviderRegistry;
import dev.langchain4j.model.chat.ChatModel;
import dev.langchain4j.model.chat.StreamingChatModel;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;

@AutoConfiguration
@EnableConfigurationProperties({AgentProperties.class, AgentChatProperties.class})
public class ModelConfig {
    @Bean
    public com.summit.harnesscore.model.ModelConfig chatModelConfig(AgentChatProperties agentChatProperties){
        return com.summit.harnesscore.model.ModelConfig.builder()
                .baseUrl(agentChatProperties.getBaseUrl())
                .apiKey(agentChatProperties.getApiKey())
                .modelName(agentChatProperties.getModelName())
                .timeout(agentChatProperties.getTimeout())
                .provider("default")
                .build();
    }
    @Bean
    public com.summit.harnesscore.model.ModelConfig streamingModelConfig(AgentProperties agentProperties){
        return com.summit.harnesscore.model.ModelConfig.builder()
                .baseUrl(agentProperties.getBaseUrl())
                .apiKey(agentProperties.getApiKey())
                .modelName(agentProperties.getModelName())
                .timeout(agentProperties.getTimeout())
                .provider("default-streaming")
                .build();
    }

    @Bean
    public StreamingChatModel defaultStreamingChatModel(ModelProviderRegistry<StreamingChatModel> streamingModelProviderRegistry, AgentProperties agentProperties){
        return streamingModelProviderRegistry.create(streamingModelConfig(agentProperties));
    }

    @Bean
    public ChatModel defaultChatModel(ModelProviderRegistry<ChatModel> chatModelProviderRegistry, AgentChatProperties agentChatProperties){
        return chatModelProviderRegistry.create(chatModelConfig(agentChatProperties));
    }

}
