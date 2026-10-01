package com.summit.harness.springbootautoconfigure.config.execution;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.summit.core.compact.Tokenizer;
import com.summit.core.conversation.ConversationManager;
import com.summit.core.conversation.event.RuntimeEventPublisher;
import com.summit.core.runtime.loop.ExecutionRepository;
import com.summit.core.runtime.*;
import com.summit.core.runtime.loop.LoopInterceptor;
import com.summit.core.runtime.loop.RuntimeBoundaryChecker;
import com.summit.core.runtime.loop.ExecutionFailureObserver;
import com.summit.core.runtime.loop.lifestyle.RuntimeLifeStyleManager;
import com.summit.core.tool.ToolExecutionManager;
import com.summit.runtime.agent.AgentConfig;
import com.summit.runtime.conversation.DefaultRuntimeFactory;
import com.summit.runtime.compact.DefaultManualCompacter;
import com.summit.runtime.compact.DefaultModelCompacter;
import com.summit.runtime.loop.BoundaryChecker;
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
                                                LoopInterceptor loopInterceptor,
                                                RuntimeBoundaryChecker boundaryChecker,
                                                List<ExecutionFailureObserver> failureObservers
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
                .failureObservers(failureObservers)
                .modelCompacter(modelCompacter)
                .boundaryChecker(boundaryChecker)
                .build();
    }

    /**
     * The default runtime boundary checker: budget limits, the two compaction bands and token
     * exhaustion.
     *
     * <p>Published as a bean so the loop's decision policy is replaceable. The checker holds no
     * per-execution state — everything it reads arrives on the {@link com.summit.core.agent.Execution}
     * it is handed — so a single shared instance is correct. An application that needs a different
     * policy declares its own {@link RuntimeBoundaryChecker} bean and this one steps aside.</p>
     */
    @Bean
    @ConditionalOnMissingBean(RuntimeBoundaryChecker.class)
    public RuntimeBoundaryChecker boundaryChecker(AgentConfig agentConfig, Tokenizer tokenizer,
                                                  ConversationManager conversationManager,
                                                  DefaultManualCompacter manualCompacter,
                                                  DefaultModelCompacter modelCompacter) {
        return new BoundaryChecker(agentConfig, tokenizer, conversationManager,
                manualCompacter, modelCompacter);
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
    @ConditionalOnMissingBean(RuntimeEventPublisher.class)
    public RuntimeEventPublisher defaultRuntimeListener(List<RuntimeListener> listeners) {
        return new RuntimeEventPublisher(listeners);
    }
}
