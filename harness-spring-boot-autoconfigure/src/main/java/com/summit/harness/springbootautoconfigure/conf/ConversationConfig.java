package com.summit.harness.springbootautoconfigure.conf;

import com.summit.harnesscore.adapter.TokenEstimator;
import com.summit.harnesscore.compact.ContextCompacter;
import com.summit.harnesscore.compact.Tokenizer;
import com.summit.harnesscore.conversation.ConversationManager;
import com.summit.harnesscore.conversation.ConversationStore;
import com.summit.harnesscore.conversation.event.RuntimeEventPublisher;
import com.summit.runtime.compact.DefaultContextCompacter;
import com.summit.runtime.conversation.DefaultConversationManager;
import com.summit.runtime.conversation.DefaultConversationStore;
import com.summit.runtime.conversation.DefaultTokenizer;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.context.annotation.Bean;


@AutoConfiguration
public class ConversationConfig {
    @Bean
    @ConditionalOnMissingBean
    public ConversationManager conversationManager(ConversationStore conversationStore, RuntimeEventPublisher runtimeEventPublisher, ContextCompacter contextCompacter){
        return new DefaultConversationManager(conversationStore,runtimeEventPublisher,contextCompacter);
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

    @Bean
    @ConditionalOnMissingBean
    public ContextCompacter contextCompacter(Tokenizer tokenizer){
        return new DefaultContextCompacter(tokenizer);
    }
}
