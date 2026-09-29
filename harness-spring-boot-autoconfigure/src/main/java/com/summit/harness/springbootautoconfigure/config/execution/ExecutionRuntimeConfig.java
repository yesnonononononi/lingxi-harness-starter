package com.summit.harness.springbootautoconfigure.config.execution;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.summit.core.compact.Tokenizer;
import com.summit.core.conversation.ConversationManager;
import com.summit.core.conversation.event.RuntimeEventPublisher;
import com.summit.core.runtime.loop.ExecutionRepository;
import com.summit.core.runtime.*;
import com.summit.core.runtime.loop.LoopInterceptor;
import com.summit.core.runtime.loop.lifestyle.RuntimeLifeStyleManager;
import com.summit.core.tool.ToolExecutionManager;
import com.summit.runtime.agent.AgentConfig;
import com.summit.runtime.conversation.DefaultRuntimeFactory;
import com.summit.runtime.compact.DefaultManualCompacter;
import com.summit.runtime.compact.DefaultModelCompacter;
import com.summit.runtime.loop.lifeStyle.DefaultRuntimeLifeStyleManager;
import com.summit.runtime.loop.control.InMemoryActiveExecutionRegistry;
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
                                                AgentConfig agentConfig, com.summit.core.runtime.loop.ContextUsageReporter usage,
                                                Tokenizer tokenizer,
                                                ExecutionRepository executionRepository,
                                                DefaultManualCompacter manualCompacter,
                                                DefaultModelCompacter modelCompacter, RuntimeLifeStyleManager runtimeLifeStyleManager,
                                                LoopInterceptor loopInterceptor
    ) {
        return DefaultRuntimeFactory.builder()
                .toolExecutionManager(defaultToolExecutionManager)
                .runtimeEventPublisher(defaultRuntimeListener)
                .conversationManager(conversationManager)
                .usage(usage)
                .tokenizer(tokenizer)
                .loopInterceptor(loopInterceptor)
                .executionRepository(executionRepository)
                .agentConfig(agentConfig)
                .manualCompacter(manualCompacter)
                .runtimeLifeStyleManager(runtimeLifeStyleManager)
                .modelCompacter(modelCompacter)
                .build();
    }

    @Bean
    @ConditionalOnMissingBean
    public LoopInterceptor defaultLoopInterceptor() {
        return LoopInterceptor.NOOP;
    }

    @Bean
    @ConditionalOnMissingBean
    public RuntimeLifeStyleManager runtimeLifeStyleListener(RuntimeEventPublisher runtimeEventPublisher) {
        return new DefaultRuntimeLifeStyleManager(runtimeEventPublisher);
    }

    @Bean
    @ConditionalOnMissingBean(ExecutionRepository.class)
    public ExecutionRepository inMemoryExecutionRepository(ObjectMapper objectMapper) {
        return new InMemoryActiveExecutionRegistry(objectMapper);
    }

    @Bean
    public RuntimeEventPublisher defaultRuntimeListener(List<RuntimeListener> listeners) {
        return new RuntimeEventPublisher(listeners);
    }
}
