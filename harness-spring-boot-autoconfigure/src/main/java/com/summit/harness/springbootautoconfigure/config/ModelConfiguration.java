package com.summit.harness.springbootautoconfigure.config;

import com.summit.harness.springbootautoconfigure.properties.agent.AgentChatProperties;
import com.summit.harness.springbootautoconfigure.properties.CompactContextModelProperties;
import com.summit.core.conf.ModelConfig;
import com.summit.core.model.ModelProviderRegistry;
import com.summit.core.model.RequestModelInvokerFactory;
import com.summit.runtime.model.DefaultRequestModelInvokerFactory;
import com.summit.adapter.langchain4j.codec.TokenEstimatorAdapter;
import com.summit.core.adapter.TokenEstimator;
import com.summit.core.model.chat.ChatModel;
import com.summit.core.model.streaming.StreamingChatModel;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;

/** Three independently configured models: chat (reasoning, thinking), stream (streaming, thinking), compact (context compaction, no thinking). */
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
    public ModelConfig compactContextModelConfig(CompactContextModelProperties compact,
                                                 AgentChatProperties chat) {
        if (!compact.isModelConfigured()) {
            log.info("No dedicated compact model configured; reusing the chat model configuration");
            return ModelConfig.builder()
                    .baseUrl(chat.getBaseUrl())
                    .apiKey(chat.getApiKey())
                    .modelName(chat.getModelName())
                    .timeout(chat.getTimeout())
                    .sendThinking(chat.isSendThinking())
                    .maxTokens(chat.getMaxTokens())
                    .reasoningEffort(chat.getReasoningEffort())
                    .returnThinking(chat.isReturnThinking())
                    .provider(chat.getProvider())
                    .build();
        }
        return ModelConfig.builder()
                .baseUrl(compact.getBaseUrl())
                .apiKey(compact.getApiKey())
                .modelName(compact.getModelName())
                .timeout(compact.getTimeout())
                .sendThinking(compact.isSendThinking())
                .maxTokens(compact.getMaxTokens())
                .reasoningEffort(compact.getReasoningEffort())
                .returnThinking(compact.isReturnThinking())
                .provider(compact.getProvider() == null || compact.getProvider().isBlank()
                        ? "default-compact" : compact.getProvider())
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
        } catch (Exception e) {
            log.warn("Error creating token count estimator for model {} {}", modelName, e.getMessage());
            return new TokenEstimatorAdapter("gpt-3.5-turbo");
        }
    }

    @Bean
    @ConditionalOnMissingBean(name = "defaultContextCompactModel")
    public ChatModel defaultContextCompactModel(
            ModelProviderRegistry<ChatModel> chatModelProviderRegistry,
            @Qualifier("compactContextModelConfig") ModelConfig compactContextModelConfig) {
        return chatModelProviderRegistry.create(compactContextModelConfig);
    }

    @Bean
    public RequestModelInvokerFactory requestModelInvokerFactory(
            AgentChatProperties properties,
            @Qualifier("chatModelConfig") ModelConfig modelConfig,
            ModelProviderRegistry<ChatModel> chatProviders,
            ModelProviderRegistry<StreamingChatModel> streamingProviders) {
        return new DefaultRequestModelInvokerFactory(properties.getProvider(), modelConfig,
                chatProviders, streamingProviders);
    }
}
