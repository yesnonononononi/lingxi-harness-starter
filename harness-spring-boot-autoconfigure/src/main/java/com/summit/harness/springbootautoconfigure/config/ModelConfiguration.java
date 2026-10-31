package com.summit.harness.springbootautoconfigure.config;

import com.summit.adapter.langchain4j.codec.token.TokenEstimatorAdapter;
import com.summit.core.adapter.TokenEstimator;
import com.summit.core.conf.ModelConfig;
import com.summit.core.model.ModelProviderRegistry;
import com.summit.core.model.ModelProviderRouting;
import com.summit.core.model.RequestModelInvokerFactory;
import com.summit.core.model.chat.ChatModel;
import com.summit.core.model.streaming.StreamingChatModel;
import com.summit.harness.springbootautoconfigure.properties.CompactContextModelProperties;
import com.summit.harness.springbootautoconfigure.properties.agent.AgentChatProperties;
import com.summit.runtime.model.DefaultRequestModelInvokerFactory;
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
                .returnThinking(agentChatProperties.isReturnThinking())
                .anthropicOptions(new ModelConfig.AnthropicOptions(
                        agentChatProperties.getThinkingType(), agentChatProperties.getThinkingBudgetTokens(),
                        agentChatProperties.isPromptCache(), agentChatProperties.getPromptCacheTtl(), agentChatProperties.isPromptCacheConversation()))
                .provider(ModelProviderRouting.chatProvider(agentChatProperties.getProvider(),
                        agentChatProperties.getModelName(), false))
                .build();
    }

    @Bean
    @ConditionalOnMissingBean(name = "compactContextModelConfig")
    public ModelConfig compactContextModelConfig(CompactContextModelProperties compact,
                                                 AgentChatProperties chat) {
        // Connection may fall back to the chat model, call semantics never do: the compact profile owns
        // the output budget and the no-thinking flags, otherwise summaries inherit chat reasoning effort
        // and spend their budget thinking instead of summarizing.
        boolean dedicated = compact.isModelConfigured();
        if (!dedicated) {
            log.info("No dedicated compact model configured; reusing the chat connection with compact behavior");
        }
        return ModelConfig.builder()
                .baseUrl(dedicated ? compact.getBaseUrl() : chat.getBaseUrl())
                .apiKey(dedicated ? compact.getApiKey() : chat.getApiKey())
                .modelName(dedicated ? compact.getModelName() : chat.getModelName())
                .timeout(dedicated ? compact.getTimeout() : chat.getTimeout())
                .sendThinking(compact.isSendThinking())
                .maxTokens(compact.getMaxTokens())
                .reasoningEffort(compact.getReasoningEffort())
                .returnThinking(compact.isReturnThinking())
                .anthropicOptions(new ModelConfig.AnthropicOptions(
                        compact.getThinkingType(), compact.getThinkingBudgetTokens(),
                        compact.isPromptCache(), compact.getPromptCacheTtl(), compact.isPromptCacheConversation()))
                .provider(ModelProviderRouting.compactProvider(
                        dedicated ? compact.getProvider() : chat.getProvider(),
                        dedicated ? compact.getModelName() : chat.getModelName()))
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
