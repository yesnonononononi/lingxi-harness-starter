package com.summit.harness.springbootautoconfigure.config.execution;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.summit.core.compact.CompactSummaryApplier;
import com.summit.core.compact.Tokenizer;
import com.summit.core.conversation.ConversationManager;
import com.summit.core.conversation.api.ResponseIdGenerator;
import com.summit.core.conversation.event.RuntimeEventPublisher;
import com.summit.core.runtime.RuntimeFactory;
import com.summit.core.runtime.RuntimeListener;
import com.summit.core.runtime.loop.*;
import com.summit.core.runtime.loop.lifestyle.RuntimeLifeStyleManager;
import com.summit.core.tool.ToolExecutionManager;
import com.summit.core.tool.ToolRegistry;
import com.summit.core.transaction.RuntimeTransactionalSupport;
import com.summit.runtime.agent.AgentConfig;
import com.summit.runtime.agent.ChatAgent;
import com.summit.runtime.audit.DefaultTokenAuditor;
import com.summit.runtime.audit.ExecutionTokenAuditRecorder;
import com.summit.runtime.compact.DefaultContextPruner;
import com.summit.runtime.compact.DefaultModelCompacter;
import com.summit.runtime.conversation.DefaultRuntimeFactory;
import com.summit.runtime.conversation.SnowflakeResponseIdGenerator;
import com.summit.runtime.loop.BoundaryChecker;
import com.summit.runtime.loop.DefaultLoopInterceptorProcessor;
import com.summit.runtime.loop.DefaultRuntimeLifeStyleManager;
import com.summit.runtime.loop.LoopInterceptorFactory;
import com.summit.runtime.loop.control.InMemoryActiveExecutionRegistry;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.List;
import java.util.concurrent.Callable;

@AutoConfiguration
public class ExecutionRuntimeConfig {
    /**
     * Configure distinct worker IDs (0..1023) for runtime instances that share response storage.
     */
    @Bean
    @ConditionalOnMissingBean(ResponseIdGenerator.class)
    public ResponseIdGenerator responseIdGenerator(
            @Value("${lingxi.agent.runtime.response-id.worker-id:0}") long workerId) {
        return workerId == 0 ? SnowflakeResponseIdGenerator.DEFAULT : new SnowflakeResponseIdGenerator(workerId);
    }

    @Bean
    @ConditionalOnMissingBean
    public RuntimeFactory defaultRuntimeFactory(RuntimeEventPublisher defaultRuntimeListener,
                                                ConversationManager conversationManager,
                                                ToolExecutionManager defaultToolExecutionManager,
                                                AgentConfig agentConfig, com.summit.core.runtime.loop.ContextUsageReporter usage,
                                                Tokenizer tokenizer,
                                                ExecutionRepository executionRepository,
                                                ExecutionControl executionControl,
                                                DefaultContextPruner contextPruner,
                                                DefaultModelCompacter modelCompacter, RuntimeLifeStyleManager runtimeLifeStyleManager,
                                                LoopInterceptorProcessor loopInterceptorProcessor,
                                                RuntimeBoundaryChecker boundaryChecker,
                                                List<ExecutionFailureObserver> failureObservers,
                                                ResponseIdGenerator responseIdGenerator,
                                                CompactSummaryApplier compactSummaryApplier,
                                                RuntimeTransactionalSupport runtimeTransactionalSupport) {
        return DefaultRuntimeFactory.builder()
                .toolExecutionManager(defaultToolExecutionManager)
                .runtimeEventPublisher(defaultRuntimeListener)
                .responseIdGenerator(responseIdGenerator)
                .conversationManager(conversationManager)
                .compactSummaryApplier(compactSummaryApplier)
                .usage(usage)
                .transactionalSupport(runtimeTransactionalSupport)
                .tokenizer(tokenizer)
                .loopInterceptorProcessor(loopInterceptorProcessor)
                .executionRepository(executionRepository)
                .executionControl(executionControl)
                .agentConfig(agentConfig)
                .contextPruner(contextPruner)
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
                                                  DefaultContextPruner contextPruner,
                                                  DefaultModelCompacter modelCompacter,
                                                  ContextUsageReporter usage, com.summit.core.tool.ToolRegistry toolRegistry) {
        return new BoundaryChecker(agentConfig, tokenizer, conversationManager,
                contextPruner, modelCompacter, usage, toolRegistry);
    }


    @Bean
    @ConditionalOnMissingBean
    public RuntimeTransactionalSupport runtimeTransactionalSupport(ObjectProvider<TransactionTemplate> transactionTemplate) {
        return new RuntimeTransactionalSupport() {
            @Override
            public <T> T executeWith(Callable<T> callback, int propagationBehavior) {
                TransactionTemplate template = transactionTemplate.getIfAvailable();
                if (template == null || template.getTransactionManager() == null) {
                    throw new UnsupportedOperationException("The transactionTemplate cannot be null");
                }
                TransactionTemplate newInstance = new TransactionTemplate(template.getTransactionManager());
                newInstance.setPropagationBehavior(propagationBehavior);
                return action(newInstance, callback);
            }

            @Override
            public <T> T execute(Callable<T> callback) {
                TransactionTemplate template = transactionTemplate.getIfAvailable();
                if (template == null) {
                    return RuntimeTransactionalSupport.direct().execute(callback);
                }
                return action(template, callback);
            }

            private <T> T action(TransactionTemplate template, Callable<T> callback) {
                return template.execute(status -> {
                    try {
                        return callback.call();
                    } catch (Exception e) {
                        status.setRollbackOnly();
                        if (e instanceof RuntimeException) {
                            throw (RuntimeException) e;
                        }
                        throw new RuntimeException(e);
                    }
                });
            }


        };
    }


    /**
     * The dispatcher orders callbacks only; runtime limits are owned by the loop.
     */
    @Bean
    @ConditionalOnMissingBean
    public LoopInterceptorProcessor defaultLoopInterceptorProcessor(LoopInterceptorFactory loopInterceptorFactory) {
        return new DefaultLoopInterceptorProcessor(loopInterceptorFactory);
    }

    @Bean
    @ConditionalOnMissingBean
    public RuntimeLifeStyleManager runtimeLifeStyleListener(RuntimeEventPublisher runtimeEventPublisher,
                                                            ObjectProvider<ExecutionTokenAuditRecorder> tokenAuditRecorder) {
        return new DefaultRuntimeLifeStyleManager(runtimeEventPublisher, tokenAuditRecorder.getIfAvailable());
    }

    @Bean
    @ConditionalOnProperty(prefix = "lingxi.agent.runtime.token-audit", name = "enabled", havingValue = "true")
    public ExecutionTokenAuditRecorder executionTokenAuditRecorder(Tokenizer tokenizer, AgentConfig agentConfig,
                                                                   RuntimeEventPublisher runtimeEventPublisher, ToolRegistry toolRegistry,
                                                                   ObjectProvider<ChatAgent> agents) {
        return new ExecutionTokenAuditRecorder(new DefaultTokenAuditor(tokenizer, agentConfig, toolRegistry,
                execution -> agents.orderedStream()
                        .flatMap(agent -> agent.disclosedMcpToolDefinitions(execution.getId()).stream())
                        .toList()), runtimeEventPublisher);
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
