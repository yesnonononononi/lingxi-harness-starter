package com.summit.harness.springbootautoconfigure.conf;

import com.summit.harnesscore.compact.ContextCompacter;
import com.summit.harnesscore.compact.Tokenizer;
import com.summit.harnesscore.conversation.ConversationManager;
import com.summit.harnesscore.conversation.event.RuntimeEventPublisher;
import com.summit.harnesscore.runtime.Workspace;
import com.summit.runtime.compact.DefaultContextCompacter;
import com.summit.runtime.conversation.DefaultConversationManager;
import com.summit.runtime.conversation.DefaultTokenizer;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.context.annotation.Bean;

@AutoConfiguration
public class ConversationConfig {
    @Bean
    @ConditionalOnMissingBean
    public ConversationManager defaultConversationManager (Workspace workspace, RuntimeEventPublisher runtimeEventPublisher){
        return new DefaultConversationManager(workspace,runtimeEventPublisher,contextCompacter());
    }

    @Bean
    @ConditionalOnMissingBean
    public Tokenizer tokenizer(){
        return new DefaultTokenizer();
    }

    @Bean
    @ConditionalOnMissingBean
    public ContextCompacter contextCompacter(){
        return new DefaultContextCompacter(tokenizer());
    }
}
