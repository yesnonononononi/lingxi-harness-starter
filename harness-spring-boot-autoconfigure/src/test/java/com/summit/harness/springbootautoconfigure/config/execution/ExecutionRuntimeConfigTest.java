package com.summit.harness.springbootautoconfigure.config.execution;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.summit.core.agent.AgentRequest;
import com.summit.core.agent.Execution;
import com.summit.core.agent.ExecutionState;
import com.summit.core.compact.Tokenizer;
import com.summit.core.conf.ModelConfig;
import com.summit.core.conversation.api.ResponseIdGenerator;
import com.summit.core.conversation.event.AgentAuditRecordedEvent;
import com.summit.core.conversation.event.AgentTokenAuditRecordedEvent;
import com.summit.core.conversation.event.RuntimeEventPublisher;
import com.summit.core.conversation.message.Message;
import com.summit.core.conversation.message.UserMessageEntity;
import com.summit.core.json.ExecutionJson;
import com.summit.core.mcp.McpToolScope;
import com.summit.core.mcp.ScopeMcpProvider;
import com.summit.core.model.RequestModelInvokerFactory;
import com.summit.core.runtime.RuntimeFactory;
import com.summit.core.runtime.RuntimeListener;
import com.summit.core.runtime.loop.*;
import com.summit.core.runtime.loop.lifestyle.RuntimeLifeStyleManager;
import com.summit.core.tool.ToolDefinition;
import com.summit.core.tool.ToolExecuteResult;
import com.summit.core.tool.ToolExecutor;
import com.summit.core.tool.ToolRegistry;
import com.summit.core.workspace.WorkspaceResolver;
import com.summit.harness.springbootautoconfigure.config.InterceptorConfig;
import com.summit.harness.springbootautoconfigure.config.agent.AgentConfiguration;
import com.summit.runtime.agent.AgentConfig;
import com.summit.runtime.agent.ChatAgent;
import com.summit.runtime.agent.DefaultChatAgent;
import com.summit.runtime.audit.ExecutionTokenAuditRecorder;
import com.summit.runtime.conversation.DefaultRuntimeFactory;
import com.summit.runtime.conversation.DefaultTokenizer;
import com.summit.runtime.conversation.SnowflakeResponseIdGenerator;
import com.summit.runtime.loop.DefaultExecutionController;
import com.summit.runtime.loop.LoopInterceptorFactory;
import com.summit.runtime.loop.control.InMemoryActiveExecutionRegistry;
import org.junit.jupiter.api.Test;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.core.env.MapPropertySource;

import java.lang.reflect.Proxy;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

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

    @Test
    void tokenAuditRecorderIsDisabledByDefault() {
        try (AnnotationConfigApplicationContext context = responseGeneratorContext()) {
            context.refresh();
            assertTrue(context.getBeansOfType(ExecutionTokenAuditRecorder.class).isEmpty());
        }
    }

    @Test
    void enablingTokenAuditUsesTheConfiguredTokenizerAndPublishesAnAudit() {
        try (AnnotationConfigApplicationContext context = responseGeneratorContext()) {
            context.getEnvironment().getPropertySources().addFirst(new MapPropertySource("audit",
                    Map.of("lingxi.agent.runtime.token-audit.enabled", "true")));
            context.registerBean(AgentConfig.class, () -> AgentConfig.builder().maxTokens(100).build());
            ToolDefinition<ToolExecutor> definition = ToolDefinition.<ToolExecutor>builder()
                    .id("lookup").name("lookup").description("Find a file")
                    .parametersJsonSchema("{\"type\":\"object\"}").maxOutput(64).timeout(5L)
                    .executor(ignored -> ToolExecuteResult.success("ok")).build();
            context.registerBean(ToolRegistry.class, () -> new ToolRegistry(List.of(definition)));
            AtomicInteger scopeReads = new AtomicInteger();
            context.registerBean("mcpAgent", ChatAgent.class, () -> new ChatAgent(
                    context.getBean(RuntimeFactory.class), null, null, null, null, null, null, null, null) {
                @Override
                public String id() { return "mcp-agent"; }

                @Override
                public List<ToolDefinition<?>> disclosedMcpToolDefinitions(String executionId) {
                    scopeReads.incrementAndGet();
                    return "audit-run".equals(executionId) ? List.of(definition) : List.of();
                }
            });
            context.registerBean("otherMcpAgent", ChatAgent.class, () -> new ChatAgent(
                    context.getBean(RuntimeFactory.class), null, null, null, null, null, null, null, null) {
                @Override
                public String id() { return "other-mcp-agent"; }

                @Override
                public List<ToolDefinition<?>> disclosedMcpToolDefinitions(String executionId) {
                    scopeReads.incrementAndGet();
                    return "other-run".equals(executionId) ? List.of(definition, definition) : List.of();
                }
            });
            context.registerBean(Tokenizer.class, () -> new DefaultTokenizer() {
                @Override
                public int count(List<Message> messages, List<ToolDefinition<?>> tools) {
                    return (messages == null || messages.isEmpty() ? 0 : 17) + tools.size() * 11;
                }
            });
            List<AgentAuditRecordedEvent> events = new ArrayList<>();
            context.registerBean(RuntimeListener.class, () -> new RuntimeListener() {
                @Override
                public void onAuditRecorded(AgentAuditRecordedEvent event) {
                    events.add(event);
                }
            });
            context.refresh();
            assertEquals(0, scopeReads.get());

            Execution execution = Execution.create(AgentRequest.builder()
                    .executionId("audit-run")
                    .messages(List.of(UserMessageEntity.from("task"))).toolList(List.of("lookup")).build(), "a-1");
            execution.startChecked();
            execution.completeChecked();
            context.getBean(RuntimeLifeStyleManager.class).onComplete(execution);

            var event = assertInstanceOf(AgentTokenAuditRecordedEvent.class, events.getFirst());
            assertEquals(1, events.size());
            assertEquals(17, event.contextAudit().userTokens().getTokenMeta());
            assertEquals(0.17, event.contextAudit().userTokens().getRatio());
            assertEquals(11, event.contextAudit().otherTokens().toolDefinitionTokens().getTokenMeta());
            assertEquals(0.11, event.contextAudit().otherTokens().toolDefinitionTokens().getRatio());
            assertEquals(11, event.contextAudit().otherTokens().mcpToolDefinitionTokens().getTokenMeta());
            assertEquals(0.11, event.contextAudit().otherTokens().mcpToolDefinitionTokens().getRatio());
            Execution other = Execution.create(AgentRequest.builder().executionId("other-run")
                    .messages(List.of(UserMessageEntity.from("other task"))).build(), "other-mcp-agent");
            other.startChecked();
            other.completeChecked();
            context.getBean(RuntimeLifeStyleManager.class).onComplete(other);
            assertEquals(2, events.size());
            AgentTokenAuditRecordedEvent otherEvent = assertInstanceOf(AgentTokenAuditRecordedEvent.class, events.getLast());
            assertEquals(0, otherEvent.contextAudit().otherTokens().toolDefinitionTokens().getTokenMeta());
            assertEquals(22, otherEvent.contextAudit().otherTokens().mcpToolDefinitionTokens().getTokenMeta());
        }
    }

    private AnnotationConfigApplicationContext responseGeneratorContext() {
        AnnotationConfigApplicationContext context = new AnnotationConfigApplicationContext();
        context.registerBean(LoopInterceptorFactory.class, () -> new LoopInterceptorFactory(Map.of()));
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
            context.getEnvironment().getPropertySources().addFirst(new MapPropertySource("imageWindow",
                    Map.of("lingxi.agent.runtime.image-window.max-images", "9",
                            "lingxi.agent.runtime.image-window.retain-images", "4")));
            context.registerBean(ExecutionRepository.class, () -> new InMemoryActiveExecutionRegistry());
            context.registerBean(RuntimeEventPublisher.class, () -> new RuntimeEventPublisher(List.of()));
            context.registerBean(RuntimeLifeStyleManager.class,
                    () -> configuration.runtimeLifeStyleListener(context.getBean(RuntimeEventPublisher.class),
                            context.getBeanProvider(ExecutionTokenAuditRecorder.class)));
            context.registerBean(RuntimeFactory.class, () -> DefaultRuntimeFactory.builder()
                    .executionControl(context.getBean(ExecutionControl.class)).build());
            context.registerBean("chatModelConfig", ModelConfig.class,
                    () -> ModelConfig.builder().baseUrl("unused").apiKey("unused").modelName("unused").build());
            context.registerBean(RequestModelInvokerFactory.class, () -> new RequestModelInvokerFactory() {
                public Selection select(String providerName) { return new Selection(null, null, false); }
                public Selection select(ModelConfig config) { return new Selection(null, null, false); }
            });
            context.registerBean(WorkspaceResolver.class, () -> (WorkspaceResolver) Proxy.newProxyInstance(
                    WorkspaceResolver.class.getClassLoader(), new Class<?>[]{WorkspaceResolver.class},
                    (proxy, method, args) -> { throw new UnsupportedOperationException("workspace is unused"); }));
            context.registerBean(ScopeMcpProvider.class, () -> config -> McpToolScope.EMPTY);
            context.register(AgentConfiguration.class);

            context.refresh();

            assertInstanceOf(DefaultChatAgent.class, context.getBean(ChatAgent.class));
            assertInstanceOf(DefaultExecutionController.class, context.getBean(ExecutionControl.class));
            assertEquals(new AgentConfig.ImageWindow(9, 4), context.getBean(AgentConfig.class).imageWindow());
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
                new LoopInterceptorFactory(Map.of("late", interceptor("late", 100, seen),
                        "early", interceptor("early", -1, seen))));

        processor.onLoopStart(context());

        assertEquals(List.of("early", "late"), seen);
    }

    @Test
    void processorAcceptsApplicationInterceptorsWithoutABoundaryOwner() {
        List<String> seen = new ArrayList<>();
        LoopInterceptorProcessor processor = configuration.defaultLoopInterceptorProcessor(
                new LoopInterceptorFactory(Map.of("business", interceptor("business", -1, seen))));

        assertEquals(InterceptorResult.NONE, processor.onLoopStart(context()));
        assertEquals(List.of("business"), seen);
    }

    @Test
    void emptyInterceptorRegistryIsValid() {
        LoopInterceptorProcessor processor = configuration.defaultLoopInterceptorProcessor(new LoopInterceptorFactory(Map.of()));

        assertEquals(InterceptorResult.NONE, processor.onLoopStart(context()));
    }

    @Test
    void defaultSignalInterceptorDoesNotConsumeTheModelBudget() {
        LoopContext context = context();
        LoopInterceptor interceptor = new InterceptorConfig().loopInterceptor();

        assertEquals(InterceptorResult.NONE, interceptor.onBeforeModelInvoke(context));
        assertEquals(0, context.getLoopMessages().getExecution().getModelAttempts());
    }

    private static LoopContext context() {
        Execution execution = Execution.builder().id("e-1").agentId("a-1")
                .executionState(ExecutionState.CREATED)
                .agentRequest(AgentRequest.builder()
                        .messages(List.of(UserMessageEntity.from("task"))).build())
                .build();
        return LoopContext.withCompactionCounter(LoopMessages.builder().execution(execution).build(),
                new ExecutionControlSignal("e-1"), () -> 0, null, messages -> { });
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
