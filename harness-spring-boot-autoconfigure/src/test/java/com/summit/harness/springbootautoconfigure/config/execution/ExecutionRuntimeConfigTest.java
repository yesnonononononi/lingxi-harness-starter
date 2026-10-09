package com.summit.harness.springbootautoconfigure.config.execution;

import com.summit.core.agent.AgentRequest;
import com.summit.core.agent.Execution;
import com.summit.core.agent.ExecutionState;
import com.summit.core.conf.ModelConfig;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.summit.core.conversation.api.ResponseIdGenerator;
import com.summit.core.conversation.event.RuntimeEventPublisher;
import com.summit.core.conversation.message.UserMessageEntity;
import com.summit.core.json.ExecutionJson;
import com.summit.core.mcp.McpToolScope;
import com.summit.core.mcp.ScopeMcpProvider;
import com.summit.core.model.RequestModelInvokerFactory;
import com.summit.core.runtime.RuntimeFactory;
import com.summit.core.runtime.loop.RuntimeBoundaryChecker;
import com.summit.core.runtime.loop.ExecutionControl;
import com.summit.core.runtime.loop.ExecutionRepository;
import com.summit.core.runtime.loop.lifestyle.RuntimeLifeStyleManager;
import com.summit.core.workspace.WorkspaceManager;
import com.summit.core.runtime.loop.ExecutionControlSignal;
import com.summit.core.runtime.loop.InterceptorResult;
import com.summit.core.runtime.loop.LoopContext;
import com.summit.core.runtime.loop.LoopInterceptor;
import com.summit.core.runtime.loop.LoopInterceptorProcessor;
import com.summit.harness.springbootautoconfigure.config.InterceptorConfig;
import com.summit.harness.springbootautoconfigure.config.agent.AgentConfiguration;
import com.summit.runtime.agent.ChatAgent;
import com.summit.runtime.agent.DefaultChatAgent;
import com.summit.runtime.conversation.DefaultRuntimeFactory;
import com.summit.runtime.conversation.SnowflakeResponseIdGenerator;
import com.summit.runtime.loop.DefaultExecutionController;
import com.summit.runtime.loop.control.InMemoryActiveExecutionRegistry;
import org.junit.jupiter.api.Test;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.core.env.MapPropertySource;

import java.lang.reflect.Proxy;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertSame;

class ExecutionRuntimeConfigTest {
    private final ExecutionRuntimeConfig configuration = new ExecutionRuntimeConfig();

    @Test
    void responseGeneratorDefaultsToTheSharedWorkerAndBindsTheConfiguredWorker() {
        try (AnnotationConfigApplicationContext context = responseGeneratorContext()) {
            context.refresh();
            assertSame(SnowflakeResponseIdGenerator.DEFAULT, context.getBean(ResponseIdGenerator.class));
        }
        try (AnnotationConfigApplicationContext context = responseGeneratorContext()) {
            context.getEnvironment().getPropertySources().addFirst(new MapPropertySource("worker",
                    Map.of("lingxi.agent.runtime.response-id.worker-id", "23")));
            context.refresh();
            long id = Long.parseLong(context.getBean(ResponseIdGenerator.class).nextId(null));
            assertEquals(23, (id >>> 12) & 1023);
        }
    }

    @Test
    void applicationResponseGeneratorReplacesTheDefaultBean() {
        ResponseIdGenerator custom = previous -> "123";
        try (AnnotationConfigApplicationContext context = responseGeneratorContext()) {
            context.registerBean("customResponseIds", ResponseIdGenerator.class, () -> custom);
            context.refresh();
            assertSame(custom, context.getBean(ResponseIdGenerator.class));
            assertEquals(1, context.getBeansOfType(ResponseIdGenerator.class).size());
        }
    }

    private AnnotationConfigApplicationContext responseGeneratorContext() {
        AnnotationConfigApplicationContext context = new AnnotationConfigApplicationContext();
        context.registerBean(ObjectMapper.class, () -> ExecutionJson.newObjectMapper());
        context.registerBean(RuntimeFactory.class, () -> DefaultRuntimeFactory.builder().build());
        context.registerBean(RuntimeBoundaryChecker.class, () -> (RuntimeBoundaryChecker) Proxy.newProxyInstance(
                RuntimeBoundaryChecker.class.getClassLoader(), new Class<?>[]{RuntimeBoundaryChecker.class},
                (proxy, method, args) -> { throw new UnsupportedOperationException("boundary is unused"); }));
        context.register(ExecutionRuntimeConfig.class);
        return context;
    }

    @Test
    void defaultAgentAndRuntimeFactoryShareTheControllerWithoutACircularDependency() {
        try (AnnotationConfigApplicationContext context = new AnnotationConfigApplicationContext()) {
            context.registerBean(ExecutionRepository.class, () -> new InMemoryActiveExecutionRegistry());
            context.registerBean(RuntimeEventPublisher.class, () -> new RuntimeEventPublisher(List.of()));
            context.registerBean(RuntimeLifeStyleManager.class,
                    () -> configuration.runtimeLifeStyleListener(context.getBean(RuntimeEventPublisher.class)));
            context.registerBean(RuntimeFactory.class, () -> DefaultRuntimeFactory.builder()
                    .executionControl(context.getBean(ExecutionControl.class)).build());
            context.registerBean("chatModelConfig", ModelConfig.class,
                    () -> ModelConfig.builder().baseUrl("unused").apiKey("unused").modelName("unused").build());
            context.registerBean(RequestModelInvokerFactory.class, () -> new RequestModelInvokerFactory() {
                public Selection select(String providerName) { return new Selection(null, null, false); }
                public Selection select(ModelConfig config) { return new Selection(null, null, false); }
            });
            context.registerBean(WorkspaceManager.class, () -> (WorkspaceManager) Proxy.newProxyInstance(
                    WorkspaceManager.class.getClassLoader(), new Class<?>[]{WorkspaceManager.class},
                    (proxy, method, args) -> { throw new UnsupportedOperationException("workspace is unused"); }));
            context.registerBean(ScopeMcpProvider.class, () -> config -> McpToolScope.EMPTY);
            context.register(AgentConfiguration.class);

            context.refresh();

            assertInstanceOf(DefaultChatAgent.class, context.getBean(ChatAgent.class));
            assertInstanceOf(DefaultExecutionController.class, context.getBean(ExecutionControl.class));
        }
    }

    @Test
    void fallbackIsAnInMemoryRegistry() {
        assertInstanceOf(InMemoryActiveExecutionRegistry.class,
                configuration.inMemoryExecutionRepository(ExecutionJson.newObjectMapper()));
    }

    @Test
    void defaultInterceptorProcessorDispatchesEveryRegisteredBeanInOrder() {
        List<String> seen = new ArrayList<>();
        LoopInterceptorProcessor processor = configuration.defaultLoopInterceptorProcessor(
                List.of(interceptor("late", 100, seen), interceptor("early", -1, seen)));

        processor.onLoopStart(context());

        assertEquals(List.of("early", "late"), seen);
    }

    @Test
    void processorAcceptsApplicationInterceptorsWithoutABoundaryOwner() {
        List<String> seen = new ArrayList<>();
        LoopInterceptorProcessor processor = configuration.defaultLoopInterceptorProcessor(
                List.of(interceptor("business", -1, seen)));

        assertEquals(InterceptorResult.NONE, processor.onLoopStart(context()));
        assertEquals(List.of("business"), seen);
    }

    @Test
    void emptyInterceptorRegistryIsValid() {
        LoopInterceptorProcessor processor = configuration.defaultLoopInterceptorProcessor(List.of());

        assertEquals(InterceptorResult.NONE, processor.onLoopStart(context()));
    }

    @Test
    void defaultSignalInterceptorDoesNotConsumeTheModelBudget() {
        LoopContext context = context();
        LoopInterceptor interceptor = new InterceptorConfig().loopInterceptor();

        assertEquals(InterceptorResult.NONE, interceptor.onBeforeModelInvoke(context));
        assertEquals(0, context.execution().getModelAttempts());
    }

    private static LoopContext context() {
        Execution execution = Execution.builder().id("e-1").agentId("a-1")
                .executionState(ExecutionState.CREATED)
                .agentRequest(AgentRequest.builder()
                        .messages(List.of(UserMessageEntity.from("task"))).build())
                .build();
        return new LoopContext(execution, new ExecutionControlSignal("e-1"), 0,
                Map.of(), messages -> { });
    }

    private static LoopInterceptor interceptor(String name, int order, List<String> seen) {
        return new LoopInterceptor() {
            @Override
            public int order() {
                return order;
            }

            @Override
            public InterceptorResult onLoopStart(LoopContext context) {
                seen.add(name);
                return InterceptorResult.NONE;
            }
        };
    }
}
