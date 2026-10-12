package com.summit.harness.springbootautoconfigure.config;

import com.summit.harness.springbootautoconfigure.properties.agent.AgentChatProperties;
import com.summit.harness.springbootautoconfigure.properties.CompactContextModelProperties;
import com.summit.core.conf.ModelConfig;
import com.summit.core.model.ModelProviderRegistry;
import com.summit.core.model.ModelProviderRouting;
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
    @ConditionalOnMissingBean(name = "chatModelConfig")
    public ModelConfig chatModelConfig(AgentChatProperties agentChatProperties) {
        return ModelConfig.builder()
                .baseUrl(agentChatProperties.getBaseUrl())
                .apiKey(agentChatProperties.getApiKey())
                .modelName(agentChatProperties.getModelName())
                .timeout(agentChatProperties.getTimeout())
                .sendThinking(agentChatProperties.isSendThinking())
                .maxTokens(agentChatProperties.getMaxTokens())
                .reasoningEffort(agentChatProperties.getReasoningEffort())
                .thinkingType(agentChatProperties.getThinkingType())
                .thinkingBudgetTokens(agentChatProperties.getThinkingBudgetTokens())
                .returnThinking(agentChatProperties.isReturnThinking())
                .provider(ModelProviderRouting.chatProvider(agentChatProperties.getProvider(),
                        agentChatProperties.getModelName(), false))
                .build();
    }

    @Bean
    @ConditionalOnMissingBean(name = "compactContextModelConfig")
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
                    .thinkingType(chat.getThinkingType())
                    .thinkingBudgetTokens(chat.getThinkingBudgetTokens())
                    .returnThinking(chat.isReturnThinking())
                    .provider(ModelProviderRouting.compactProvider(chat.getProvider(), chat.getModelName()))
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
                .thinkingType(compact.getThinkingType())
                .thinkingBudgetTokens(compact.getThinkingBudgetTokens())
                .returnThinking(compact.isReturnThinking())
                .provider(ModelProviderRouting.compactProvider(compact.getProvider(), compact.getModelName()))
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
    @ConditionalOnMissingBean(RequestModelInvokerFactory.class)
    public RequestModelInvokerFactory requestModelInvokerFactory(
            AgentChatProperties properties,
            @Qualifier("chatModelConfig") ModelConfig modelConfig,
            ModelProviderRegistry<ChatModel> chatProviders,
            ModelProviderRegistry<StreamingChatModel> streamingProviders) {
        return new DefaultRequestModelInvokerFactory(properties.getProvider(), modelConfig,
                chatProviders, streamingProviders);
    }
}
