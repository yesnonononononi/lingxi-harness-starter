package com.summit.harness.springbootautoconfigure.conf;

import com.summit.core.adapter.TokenEstimator;
import com.summit.core.compact.Tokenizer;
import com.summit.core.compact.ContextAttachmentProvider;
import com.summit.core.conversation.ConversationManager;
import com.summit.core.conversation.ConversationStore;
import com.summit.core.conversation.event.RuntimeEventPublisher;
import com.summit.core.model.ChatModel;
import com.summit.core.workspace.WorkspaceManager;
import com.summit.harness.springbootautoconfigure.properties.agent.AgentChatProperties;
import com.summit.runtime.agent.AgentConfig;
import com.summit.runtime.compact.DefaultManualCompacter;
import com.summit.runtime.compact.DefaultModelCompacter;
import com.summit.runtime.conversation.DefaultConversationManager;
import com.summit.runtime.conversation.DefaultConversationStore;
import com.summit.runtime.conversation.DefaultTokenizer;
import com.summit.runtime.conversation.SystemPromptAssembler;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.context.annotation.Bean;


@AutoConfiguration
public class ConversationConfig {
    @Bean
    @ConditionalOnMissingBean
    public ConversationManager conversationManager(ConversationStore conversationStore, RuntimeEventPublisher runtimeEventPublisher, AgentChatProperties agentChatProperties, ContextAttachmentProvider contextAttachmentProvider, WorkspaceManager workspaceManager){
        return new DefaultConversationManager(conversationStore,runtimeEventPublisher,
                new SystemPromptAssembler(), agentChatProperties.getSystemPrompt(), contextAttachmentProvider, workspaceManager);
    }

    @Bean
    @ConditionalOnMissingBean
    public ConversationStore conversationStore(){
        return new DefaultConversationStore();
    }

    @Bean
    @ConditionalOnMissingBean
    public Tokenizer tokenizer(TokenEstimator tokenEstimator){
        return new DefaultTokenizer(tokenEstimator);
    }

    /**
     * Manual per-round truncation compaction (shouldSqueeze band); triggered by the runtime checkpoint and blocking.
     */
    @Bean
    @ConditionalOnMissingBean
    public DefaultManualCompacter manualCompacter(ConversationStore conversationStore, ContextAttachmentProvider contextAttachmentProvider,
                                                  Tokenizer tokenizer, AgentConfig agentConfig,
                                                  RuntimeEventPublisher runtimeEventPublisher) {
        return new DefaultManualCompacter(conversationStore, contextAttachmentProvider, tokenizer, agentConfig, runtimeEventPublisher);
    }

    /**
     * Model deep compaction (expectAdvanceSqueeze band): summarizes the session with the compact model
     * and rebuilds it. Same trigger and blocking semantics as {@link #manualCompacter}.
     */
    @Bean
    @ConditionalOnMissingBean
    public DefaultModelCompacter modelCompacter(@Qualifier("defaultContextCompactModel") ChatModel compactModel,
                                                ConversationManager conversationManager, ContextAttachmentProvider contextAttachmentProvider,
                                                Tokenizer tokenizer, AgentConfig agentConfig,
                                                RuntimeEventPublisher runtimeEventPublisher) {
        return new DefaultModelCompacter(compactModel, conversationManager, contextAttachmentProvider, tokenizer, agentConfig, runtimeEventPublisher);
    }

    @Bean
    @ConditionalOnMissingBean
    public ContextAttachmentProvider contextAttachmentProvider() {
        return ContextAttachmentProvider.NONE;
    }
}
