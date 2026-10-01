package com.summit.harness.springbootautoconfigure.config;

import com.summit.core.adapter.TokenEstimator;
import com.summit.core.compact.Tokenizer;
import com.summit.core.compact.ContextAttachmentProvider;
import com.summit.core.conversation.ConversationManager;
import com.summit.core.conversation.api.ConversationTranscriptSink;
import com.summit.core.conversation.event.RuntimeEventPublisher;
import com.summit.core.model.chat.ChatModel;
import com.summit.core.prompt.PromptAssembler;
import com.summit.core.runtime.loop.ContextUsageReporter;
import com.summit.harness.springbootautoconfigure.properties.agent.AgentChatProperties;
import com.summit.runtime.agent.AgentConfig;
import com.summit.runtime.compact.DefaultManualCompacter;
import com.summit.runtime.compact.DefaultModelCompacter;
import com.summit.runtime.conversation.DefaultConversationManager;
import com.summit.runtime.conversation.DefaultTokenizer;
import com.summit.runtime.prompt.SystemPromptAssembler;
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
                                                   ObjectProvider<ConversationTranscriptSink> conversationTranscriptSink,
                                                   ObjectProvider<PromptAssembler> promptAssembler
    ) {
        return new DefaultConversationManager(
                conversationTranscriptSink.getIfAvailable(),
                contextAttachmentProvider,
                () -> promptAssembler.getIfAvailable(SystemPromptAssembler::new)
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

    /**
     * The local truncation compacter of the first squeeze band.
     *
     * <p>Gated by name, not by {@code ContextCompacter.class}: both compacters implement that
     * interface and coexist in the same context, so a type-level condition would make either one
     * cancel the other depending on registration order.</p>
     */
    @Bean
    @ConditionalOnMissingBean(name = "manualCompacter")
    public DefaultManualCompacter manualCompacter(ContextAttachmentProvider attachments, Tokenizer tokenizer,
            ContextUsageReporter usage) {
        return new DefaultManualCompacter(attachments, tokenizer, usage);
    }

    @Bean
    @ConditionalOnMissingBean(name = "modelCompacter")
    public DefaultModelCompacter modelCompacter(@Qualifier("defaultContextCompactModel") ChatModel model,
            ConversationManager conversations, ContextAttachmentProvider attachments,
            ContextUsageReporter usage) {
        return new DefaultModelCompacter(model, conversations, attachments, usage);
    }

    @Bean
    @ConditionalOnMissingBean(name = "contextAttachmentProvider")
    public ContextAttachmentProvider contextAttachmentProvider() {
        return ContextAttachmentProvider.NONE;
    }
}
