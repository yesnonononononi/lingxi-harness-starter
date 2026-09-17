package com.summit.harness.springbootautoconfigure.conf;

import com.summit.harness.springbootautoconfigure.properties.agent.AgentChatProperties;
import com.summit.harness.springbootautoconfigure.properties.CompactContextModelProperties;
import com.summit.core.conversation.event.RuntimeEventPublisher;
import com.summit.core.model.ModelConfig;
import com.summit.core.model.ModelProviderRegistry;
import com.summit.core.model.RequestModelInvokerFactory;
import com.summit.runtime.model.DefaultRequestModelInvokerFactory;
import com.summit.adapter.langchain4j.codec.TokenEstimatorAdapter;
import com.summit.core.adapter.TokenEstimator;
import com.summit.core.model.ChatModel;
import com.summit.core.model.streaming.StreamingChatModel;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;

/**
 * Three independently configured models: chat (reasoning, thinking), stream (streaming, thinking), compact (context compaction, no thinking).
 * Users can configure via yaml properties, or implement their own Provider and select it through the provider field.
 */
@Slf4j
@AutoConfiguration
@EnableConfigurationProperties({AgentChatProperties.class, CompactContextModelProperties.class})
public class ModelConfiguration {

    @Bean
    public ModelConfig chatModelConfig(AgentChatProperties agentChatProperties) {
        return ModelConfig.builder()
                .baseUrl(agentChatProperties.getBaseUrl())
                .apiKey(agentChatProperties.getApiKey())
                .modelName(agentChatProperties.getModelName())
                .timeout(agentChatProperties.getTimeout())
                .sendThinking(agentChatProperties.isSendThinking())
                .maxTokens(agentChatProperties.getMaxTokens())
                .reasoningEffort(agentChatProperties.getReasoningEffort())
                .returnThinking(agentChatProperties.isReturnThinking())
                .provider(agentChatProperties.getProvider())
                .build();
    }

 
    @Bean
    public ModelConfig compactContextModelConfig(CompactContextModelProperties compactContextModelProperties) {
        return ModelConfig.builder()
                .baseUrl(compactContextModelProperties.getBaseUrl())
                .apiKey(compactContextModelProperties.getApiKey())
                .modelName(compactContextModelProperties.getModelName())
                .timeout(compactContextModelProperties.getTimeout())
                .sendThinking(compactContextModelProperties.isSendThinking())
                .maxTokens(compactContextModelProperties.getMaxTokens())
                .reasoningEffort(compactContextModelProperties.getReasoningEffort())
                .returnThinking(compactContextModelProperties.isReturnThinking())
                .provider(compactContextModelProperties.getProvider())
                .build();
    }

    @Bean
    @ConditionalOnMissingBean
    public TokenEstimator tokenEstimator(AgentChatProperties agentChatProperties) {
        String modelName = agentChatProperties.getModelName();
        if (modelName == null || modelName.isBlank()) {
            modelName = "gpt-3.5-turbo"; // default cl100k_base
        }
        try {
            return new TokenEstimatorAdapter(modelName);
        }catch (Exception e){
            log.warn("Error creating token count estimator for model {} {}", modelName, e.getMessage());
            return new TokenEstimatorAdapter("gpt-3.5-turbo");
        }
    }

    @Bean
    @ConditionalOnMissingBean(name = "defaultContextCompactModel")
    public ChatModel defaultContextCompactModel(
            ModelProviderRegistry<ChatModel> chatModelProviderRegistry,
            @Qualifier("compactContextModelConfig") ModelConfig compactContextModelConfig) {
        compactContextModelConfig.setProvider("default-compact");
        return chatModelProviderRegistry.create(compactContextModelConfig);
    }

    @Bean
    public RequestModelInvokerFactory requestModelInvokerFactory(
            AgentChatProperties properties,
            @Qualifier("chatModelConfig") ModelConfig modelConfig,
            ModelProviderRegistry<ChatModel> chatProviders,
            ModelProviderRegistry<StreamingChatModel> streamingProviders,
            RuntimeEventPublisher runtimeEventPublisher) {
        return new DefaultRequestModelInvokerFactory(properties.getProvider(), modelConfig,
                chatProviders, streamingProviders, runtimeEventPublisher);
    }
}
