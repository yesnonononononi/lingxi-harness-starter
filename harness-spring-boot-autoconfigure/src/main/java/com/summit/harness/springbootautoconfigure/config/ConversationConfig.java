package com.summit.harness.springbootautoconfigure.config;

import com.summit.core.adapter.TokenEstimator;
import com.summit.core.compact.Tokenizer;
import com.summit.core.compact.ContextAttachmentProvider;
import com.summit.core.conversation.ConversationManager;
import com.summit.core.conversation.api.ConversationTranscriptSink;
import com.summit.core.conversation.event.RuntimeEventPublisher;
import com.summit.core.model.chat.ChatModel;
import com.summit.core.runtime.loop.ContextUsageReporter;
import com.summit.harness.springbootautoconfigure.properties.agent.AgentChatProperties;
import com.summit.runtime.agent.AgentConfig;
import com.summit.runtime.compact.DefaultManualCompacter;
import com.summit.runtime.compact.DefaultModelCompacter;
import com.summit.runtime.conversation.DefaultConversationManager;
import com.summit.runtime.conversation.DefaultTokenizer;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.context.annotation.Bean;

@AutoConfiguration
public class ConversationConfig {
    /**
     * No prompt is supplied here.
     *
     * <p>The framework holds no system prompt of its own: the text a model is given comes from
     * {@code AgentRequest.systemPrompt}, which the application fills. A framework-level default was
     * the one place the prompt could be changed without the application noticing, and it turned
     * "what prompt is this run using" into a question about configuration files rather than about
     * the request.</p>
     */
    @Bean
    @ConditionalOnMissingBean
    public ConversationManager conversationManager(ContextAttachmentProvider contextAttachmentProvider,
                                                   ObjectProvider<ConversationTranscriptSink> conversationTranscriptSink
    ) {
        return new DefaultConversationManager(
                conversationTranscriptSink.getIfAvailable(),
                contextAttachmentProvider
        );
    }

    @Bean
    @ConditionalOnMissingBean
    public Tokenizer tokenizer(TokenEstimator tokenEstimator) {
        return new DefaultTokenizer(tokenEstimator);
    }

    @Bean
    @ConditionalOnMissingBean
    public ContextUsageReporter contextUsageReporter(
            Tokenizer tokenizer, AgentConfig config, RuntimeEventPublisher publisher,
            AgentChatProperties agentChatProperties) {
        return new ContextUsageReporter(tokenizer, config.maxTokens(), publisher,
                agentChatProperties.getUsageReportInterval());
    }

    @Bean
    @ConditionalOnMissingBean
    public DefaultManualCompacter manualCompacter(ContextAttachmentProvider attachments, Tokenizer tokenizer,
            ContextUsageReporter usage) {
        return new DefaultManualCompacter(attachments, tokenizer, usage);
    }

    @Bean
    @ConditionalOnMissingBean
    public DefaultModelCompacter modelCompacter(@Qualifier("defaultContextCompactModel") ChatModel model,
            ConversationManager conversations, ContextAttachmentProvider attachments,
            ContextUsageReporter usage) {
        return new DefaultModelCompacter(model, conversations, attachments, usage);
    }

    @Bean
    @ConditionalOnMissingBean
    public ContextAttachmentProvider contextAttachmentProvider() {
        return ContextAttachmentProvider.NONE;
    }
}
