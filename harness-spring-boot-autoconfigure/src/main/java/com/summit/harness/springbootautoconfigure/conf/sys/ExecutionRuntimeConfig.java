package com.summit.harness.springbootautoconfigure.conf.sys;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.summit.core.compact.Tokenizer;
import com.summit.core.conversation.ConversationManager;
import com.summit.core.conversation.event.RuntimeEventPublisher;
import com.summit.core.runtime.loop.AgentLoopHook;
import com.summit.core.runtime.loop.ExecutionRepository;
import com.summit.core.runtime.*;
import com.summit.core.runtime.loop.lifstyle.RuntimeLifeStyleManager;
import com.summit.core.tool.ToolExecutionManager;
import com.summit.runtime.agent.AgentConfig;
import com.summit.runtime.conversation.DefaultRuntimeFactory;
import com.summit.runtime.compact.DefaultManualCompacter;
import com.summit.runtime.compact.DefaultModelCompacter;
import com.summit.runtime.loop.lifeStyle.DefaultRuntimeLifeStyleManager;
import com.summit.runtime.loop.control.InMemoryActiveExecutionRegistry;
import org.springframework.beans.factory.ObjectProvider;
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
                                                Tokenizer tokenizer,
                                                ObjectProvider<AgentLoopHook> agentLoopHook,
                                                ExecutionRepository executionRepository,
                                                DefaultManualCompacter manualCompacter,
                                                DefaultModelCompacter modelCompacter, RuntimeLifeStyleManager runtimeLifeStyleManager){
        return DefaultRuntimeFactory.builder()
                .toolExecutionManager(defaultToolExecutionManager)
                .runtimeEventPublisher(defaultRuntimeListener)
                .conversationManager(conversationManager)
                .objectMapper(objectMapper)
                .tokenizer(tokenizer)
                .agentLoopHook(agentLoopHook.getIfAvailable(() -> AgentLoopHook.NOOP))
                .activeExecutionRegistry(executionRepository)
                .executionRepository(executionRepository)
                .agentConfig(agentConfig)
                .manualCompacter(manualCompacter)
                .runtimeLifeStyleManager(runtimeLifeStyleManager)
                .modelCompacter(modelCompacter)
                .build();
    }


    @Bean
    @ConditionalOnMissingBean
    public RuntimeLifeStyleManager runtimeLifeStyleListener(RuntimeEventPublisher runtimeEventPublisher){
        return new DefaultRuntimeLifeStyleManager(runtimeEventPublisher);
    }

    /**
     * Fallback execution repository, registered only when the application supplies none.
     *
     * <p>Applications integrate by simply declaring their own {@link ExecutionRepository} bean —
     * whether process-local, snapshot-persisting, or backed by shared infrastructure. The runtime
     * attaches no semantics to which one is in play; it only needs {@code register} to hand back a
     * cooperative control signal that the loop observes at its boundaries, and {@code save} to
     * receive a recoverable snapshot at each committed round.</p>
     *
     * <p>When no application bean exists this yields a purely in-memory registry: executions are
     * controllable within the process but leave no durable trace.</p>
     */
    @Bean
    @ConditionalOnMissingBean(ExecutionRepository.class)
    public ExecutionRepository inMemoryExecutionRepository() {
        return new InMemoryActiveExecutionRegistry();
    }

    @Bean
    public RuntimeEventPublisher defaultRuntimeListener(List<RuntimeListener> listeners){
        return new RuntimeEventPublisher(listeners);
    }
}
