package com.summit.harness.springbootautoconfigure.config.agent;

import com.summit.core.agent.AgentRequest;
import com.summit.core.agent.Execution;
import com.summit.core.agent.ExecutionState;
import com.summit.core.conf.McpConfig;
import com.summit.core.conf.ModelConfig;
import com.summit.core.conf.SensitiveDataProvider;
import com.summit.core.conf.SensitiveDataSpec;
import com.summit.core.conversation.event.RuntimeEventPublisher;
import com.summit.core.mcp.McpToolScope;
import com.summit.core.mcp.ScopeMcpProvider;
import com.summit.core.model.RequestModelInvokerFactory;
import com.summit.core.runtime.RuntimeFactory;
import com.summit.core.runtime.loop.ExecutionRepository;
import com.summit.core.runtime.loop.lifestyle.RuntimeLifeStyleManager;
import com.summit.core.workspace.WorkspaceResolver;
import com.summit.runtime.agent.ChatAgent;
import com.summit.runtime.loop.DefaultRuntimeLifeStyleManager;
import com.summit.runtime.loop.control.InMemoryActiveExecutionRegistry;
import com.summit.runtime.workspace.DefaultWorkspaceResolver;
import com.summit.runtime.workspace.LocalWorkspaceProvider;
import org.junit.jupiter.api.Test;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class AgentSensitiveDataConfigurationTest {
    @Test
    void genericProvidersAreInjectedIndependentlyIntoTheDefaultAgent() {
        try (AnnotationConfigApplicationContext context = new AnnotationConfigApplicationContext()) {
            context.registerBean(ExecutionRepository.class, () -> new InMemoryActiveExecutionRegistry());
            context.registerBean(RuntimeEventPublisher.class, () -> new RuntimeEventPublisher(List.of()));
            context.registerBean(RuntimeLifeStyleManager.class,
                    () -> new DefaultRuntimeLifeStyleManager(context.getBean(RuntimeEventPublisher.class)));
            context.registerBean(RuntimeFactory.class, () -> (invoker, workspace, scope) -> execution -> execution);
            context.registerBean("chatModelConfig", ModelConfig.class,
                    () -> ModelConfig.builder().provider("application").build());
            context.registerBean(RequestModelInvokerFactory.class, () -> new RequestModelInvokerFactory() {
                public Selection select(String provider) { throw new AssertionError("configuration is required"); }
                public Selection select(ModelConfig config) {
                    assertEquals("resolved-model", config.getProvider());
                    return new Selection("test", command -> null, false);
                }
            });
            context.registerBean(WorkspaceResolver.class,
                    () -> new DefaultWorkspaceResolver(List.of(new LocalWorkspaceProvider())));
            context.registerBean(ScopeMcpProvider.class, () -> config -> McpToolScope.EMPTY);
            context.register(Providers.class, AgentConfiguration.class);
            context.refresh();

            Execution execution = Execution.builder().id("spring-sensitive").executionState(ExecutionState.SUSPENDED)
                    .agentRequest(AgentRequest.builder()
                            .modelConfigSpec(SensitiveDataSpec.builder().id(31L).version(7).build())
                            .mcpConfigSpec(SensitiveDataSpec.builder().id(99L).version(3).build()).build()).build();
            assertSame(execution, context.getBean(ChatAgent.class).execute(execution));
            Providers providers = context.getBean(Providers.class);
            assertTrue(providers.modelResolved);
            assertTrue(providers.mcpResolved);
        }
    }

    @Configuration(proxyBeanMethods = false)
    static class Providers {
        private boolean modelResolved;
        private boolean mcpResolved;

        @Bean
        SensitiveDataProvider<ModelConfig> modelRevisions() {
            return (id, version) -> {
                assertEquals(31L, id.longValue());
                assertEquals(7, version);
                modelResolved = true;
                return ModelConfig.builder().provider("resolved-model").build();
            };
        }

        @Bean
        SensitiveDataProvider<McpConfig> mcpRevisions() {
            return (id, version) -> {
                assertEquals(99L, id.longValue());
                assertEquals(3, version);
                mcpResolved = true;
                return new McpConfig();
            };
        }
    }
}
