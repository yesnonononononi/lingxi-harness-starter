package com.summit.harness.springbootautoconfigure.config;

import com.summit.core.adapter.TokenEstimator;
import com.summit.core.compact.ContextAttachmentProvider;
import com.summit.core.compact.Tokenizer;
import com.summit.core.conf.ModelConfig;
import com.summit.core.conf.SensitiveDataProvider;
import com.summit.core.conversation.ConversationManager;
import com.summit.core.conversation.api.ConversationTranscriptSink;
import com.summit.core.conversation.event.RuntimeEventPublisher;
import com.summit.core.model.chat.ChatModel;
import com.summit.core.model.ModelProviderRegistry;
import com.summit.core.prompt.PromptAssembler;
import com.summit.core.runtime.loop.ContextUsageReporter;
import com.summit.core.runtime.loop.ExecutionRepository;
import com.summit.core.skill.SkillLoader;
import com.summit.core.skill.SkillResolver;
import com.summit.harness.springbootautoconfigure.properties.agent.AgentChatProperties;
import com.summit.runtime.agent.AgentConfig;
import com.summit.runtime.compact.DefaultContextPruner;
import com.summit.runtime.compact.DefaultModelCompacter;
import com.summit.runtime.compact.RequestCompactModelResolver;
import com.summit.runtime.conversation.DefaultConversationManager;
import com.summit.runtime.conversation.DefaultTokenizer;
import com.summit.runtime.prompt.SystemPromptAssembler;
import com.summit.runtime.skill.DefaultSkillResolver;
import com.summit.runtime.skill.FileSystemSkillLoader;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.List;

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
    public ConversationManager conversationManager(List<ContextAttachmentProvider> contextAttachmentProviders,
                                                   ObjectProvider<ConversationTranscriptSink> conversationTranscriptSink,
                                                   ObjectProvider<PromptAssembler> promptAssembler,
                                                   SkillLoader skillLoader,
                                                   Tokenizer tokenizer,
                                                   AgentConfig agentConfig) {
        return new DefaultConversationManager(
                tokenizer, agentConfig.maxTokens(), conversationTranscriptSink.getIfAvailable(),
                ContextAttachmentProvider.compose(contextAttachmentProviders),
                () -> promptAssembler.getIfAvailable(SystemPromptAssembler::new),
                skillLoader
        );
    }


    @Bean
    @ConditionalOnMissingBean
    public SkillLoader skillLoader(ObjectProvider<SkillResolver> skillResolver){
        return new FileSystemSkillLoader(skillResolver.getIfAvailable());
    }

    @Configuration(proxyBeanMethods = false)
    @ConditionalOnClass(name = {
            "org.commonmark.ext.front.matter.YamlFrontMatterVisitor",
            "org.commonmark.parser.Parser"
    })
    static class DefaultSkillResolverConfiguration {
        @Bean
        @ConditionalOnMissingBean(SkillResolver.class)
        SkillResolver skillResolver() {
            return new DefaultSkillResolver();
        }
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
     * The local history pruner of the fallback squeeze band.
     *
     * <p>Gated by name, not by {@code ContextCompacter.class}: both compacters implement that
     * interface and coexist in the same context, so a type-level condition would make either one
     * cancel the other depending on registration order.</p>
     */
    @Bean
    @ConditionalOnMissingBean(name = "contextPruner")
    public DefaultContextPruner contextPruner(AgentConfig config, ContextUsageReporter usage,
                                             List<ContextAttachmentProvider> attachments) {
        return new DefaultContextPruner(config, usage, ContextAttachmentProvider.compose(attachments));
    }

    @Bean
    @ConditionalOnMissingBean(name = "modelCompacter")
    public DefaultModelCompacter modelCompacter(RequestCompactModelResolver models, ExecutionRepository executions,
            ConversationManager conversations, List<ContextAttachmentProvider> attachments,
            ContextUsageReporter usage) {
        return new DefaultModelCompacter(models::resolve, executions, conversations,
                ContextAttachmentProvider.compose(attachments), usage);
    }

    @Bean
    @ConditionalOnMissingBean
    public RequestCompactModelResolver requestCompactModelResolver(@Qualifier("chatModelConfig") ModelConfig config,
            ObjectProvider<SensitiveDataProvider<ModelConfig>> configurations, ModelProviderRegistry<ChatModel> providers,
            @Qualifier("defaultContextCompactModel") ChatModel applicationCompactModel) {
        return new RequestCompactModelResolver(config, configurations.getIfAvailable(), providers, applicationCompactModel);
    }

    @Bean
    @ConditionalOnMissingBean(ContextAttachmentProvider.class)
    public ContextAttachmentProvider contextAttachmentProvider() {
        return ContextAttachmentProvider.NONE;
    }
}
