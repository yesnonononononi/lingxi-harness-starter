package com.summit.harness.springbootautoconfigure.conf.sys;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.summit.core.compact.Tokenizer;
import com.summit.core.conversation.ConversationManager;
import com.summit.core.conversation.event.RuntimeEventPublisher;
import com.summit.core.runtime.LifeStyleCommandRegistry;
import com.summit.core.runtime.LifeStyleHandler;
import com.summit.core.runtime.RuntimeFactory;
import com.summit.core.runtime.RuntimeListener;
import com.summit.core.tool.ToolExecutionManager;
import com.summit.runtime.DefaultLifeStyleCommandRegistry;
import com.summit.runtime.DefaultLifeStyleHandler;
import com.summit.runtime.agent.AgentConfig;
import com.summit.runtime.DefaultRuntimeFactory;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.context.annotation.Bean;

import java.util.List;

@AutoConfiguration
public class ExecutionRuntimeConfig {
    @Bean
    @ConditionalOnMissingBean
    public RuntimeFactory defaultRuntimeFactory(RuntimeEventPublisher defaultRuntimeListener,
                                                ConversationManager conversationManager,
                                                ToolExecutionManager defaultToolExecutionManager,
                                                AgentConfig agentConfig, ObjectMapper objectMapper,
                                                LifeStyleHandler lifeStyleHandler,
                                                Tokenizer tokenizer,
                                                LifeStyleCommandRegistry lifeStyleCommandRegistry){
        return DefaultRuntimeFactory.builder()
                .toolExecutionManager(defaultToolExecutionManager)
                .runtimeEventPublisher(defaultRuntimeListener)
                .conversationManager(conversationManager)
                .objectMapper(objectMapper)
                .lifeStyleHandler(lifeStyleHandler)
                .tokenizer(tokenizer)
                .lifeStyleCommandRegistry(lifeStyleCommandRegistry)
                .agentConfig(agentConfig)
                .build();
    }


    @Bean
    @ConditionalOnMissingBean
    public LifeStyleHandler defaultLifeStyleHandler(){
        return new DefaultLifeStyleHandler();
    }

    @Bean
    @ConditionalOnMissingBean
    public LifeStyleCommandRegistry lifeStyleCommandRegistry(){
        return new DefaultLifeStyleCommandRegistry();
    }

    @Bean
    public RuntimeEventPublisher defaultRuntimeListener(List<RuntimeListener> listeners){
        return new RuntimeEventPublisher(listeners);
    }
}
