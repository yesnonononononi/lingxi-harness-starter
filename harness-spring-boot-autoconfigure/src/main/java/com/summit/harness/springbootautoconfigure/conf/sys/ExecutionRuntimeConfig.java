package com.summit.harness.springbootautoconfigure.conf.sys;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.summit.core.compact.Tokenizer;
import com.summit.core.conversation.ConversationManager;
import com.summit.core.conversation.event.RuntimeEventPublisher;
import com.summit.core.runtime.*;
import com.summit.core.internalUtils.plan.PlanLoopHook;
import com.summit.core.tool.ToolExecutionManager;
import com.summit.runtime.lifeStyle.DefaultLifeStyleCommandRegistry;
import com.summit.runtime.lifeStyle.DefaultLifeStyleHandler;
import com.summit.runtime.agent.AgentConfig;
import com.summit.runtime.conversation.DefaultRuntimeFactory;
import com.summit.runtime.compact.DefaultManualCompacter;
import com.summit.runtime.compact.DefaultModelCompacter;
import com.summit.runtime.lifeStyle.DefaultRuntimeLifeStyleManager;
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
                                                LifeStyleCommandRegistry lifeStyleCommandRegistry,
                                                PlanLoopHook planLoopHook,
                                                DefaultManualCompacter manualCompacter,
                                                DefaultModelCompacter modelCompacter, RuntimeLifeStyleManager runtimeLifeStyleManager){
        return DefaultRuntimeFactory.builder()
                .toolExecutionManager(defaultToolExecutionManager)
                .runtimeEventPublisher(defaultRuntimeListener)
                .conversationManager(conversationManager)
                .objectMapper(objectMapper)
                .lifeStyleHandler(lifeStyleHandler)
                .tokenizer(tokenizer)
                .lifeStyleCommandRegistry(lifeStyleCommandRegistry)
                .planLoopHook(planLoopHook)
                .agentConfig(agentConfig)
                .manualCompacter(manualCompacter)
                .runtimeLifeStyleManager(runtimeLifeStyleManager)
                .modelCompacter(modelCompacter)
                .build();
    }


    @Bean
    @ConditionalOnMissingBean
    public RuntimeLifeStyleManager runtimeLifeStyleManager(RuntimeEventPublisher runtimeEventPublisher, ConversationManager conversationManager){
        return new DefaultRuntimeLifeStyleManager(runtimeEventPublisher,conversationManager);
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
