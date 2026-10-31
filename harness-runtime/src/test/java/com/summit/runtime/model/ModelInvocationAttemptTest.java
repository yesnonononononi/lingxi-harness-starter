package com.summit.runtime.model;

import com.summit.core.agent.AgentRequest;
import com.summit.core.agent.Execution;
import com.summit.core.compact.ContextAttachmentProvider;
import com.summit.core.conf.ModelRetryConfig;
import com.summit.core.conversation.api.ChatRequestEntity;
import com.summit.core.conversation.api.ChatResponseEntity;
import com.summit.core.conversation.event.RuntimeEventPublisher;
import com.summit.core.conversation.message.AiMessageEntity;
import com.summit.core.conversation.message.TokenUsageEntity;
import com.summit.core.conversation.message.UserMessageEntity;
import com.summit.core.model.ModelChatCommand;
import com.summit.core.model.ModelInvocationUsage;
import com.summit.core.exception.RetriableModelException;
import com.summit.runtime.loop.BoundaryChecker;
import com.summit.runtime.agent.AgentConfig;
import com.summit.runtime.conversation.DefaultTokenizer;
import com.summit.core.runtime.loop.ExecutionControlSignal;
import com.summit.core.tool.ToolDefinition;
import com.summit.core.tool.ToolExecuteCommand;
import com.summit.core.tool.ToolExecuteResult;
import com.summit.core.tool.ToolExecutionManager;
import com.summit.core.tool.ToolRegistry;
import com.summit.core.utils.RetryUtil;
import com.summit.runtime.context.RuntimeContext;
import com.summit.runtime.conversation.DefaultConversationManager;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.io.IOException;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Function;
import java.util.function.Supplier;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;

class ModelInvocationAttemptTest {
    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void successfulCallConsumesExactlyOneCommand(boolean streaming) throws Exception {
        Fixture fixture = new Fixture(streaming, null);
        List<ModelChatCommand> commands = new ArrayList<>();
        AtomicInteger calls = new AtomicInteger();
        AbstractInvoker invoker = fixture.invoker(request -> {
            calls.incrementAndGet();
            return response();
        });

        ChatResponseEntity result = invoker.invoke(fixture.attempts(commands));

        assertEquals(1, calls.get());
        assertEquals(1, commands.size());
        assertEquals("1", result.getResponseId());
        assertEquals(result.getResponseId(), fixture.execution.getLastResponseId());
        assertFalse(commands.getFirst().requireRetry());
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void absentOrDisabledSettingsDoNotRetryFailures(boolean streaming) {
        for (ModelRetryConfig settings : new ModelRetryConfig[] {null, ModelRetryConfig.builder().enabled(false)
                .maxRetries(2).initialDelay(Duration.ZERO).maxDelay(Duration.ZERO).build()}) {
            Fixture fixture = new Fixture(streaming, settings);
            List<ModelChatCommand> commands = new ArrayList<>();
            AtomicInteger calls = new AtomicInteger();
            AbstractInvoker invoker = fixture.invoker(request -> {
                calls.incrementAndGet();
                throw new CompletionException(new RetriableModelException("temporary", null));
            });

            assertThrows(RuntimeException.class, () -> invoker.invoke(fixture.attempts(commands)));
            assertEquals(1, calls.get());
            assertEquals(1, commands.size());
            assertEquals("1", fixture.execution.getLastResponseId());
        }
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void retriesRebuildOnlyAttemptStateAndRetainRoundInput(boolean streaming) throws Exception {
        Fixture fixture = new Fixture(streaming, retrySettings(2));
        List<ModelChatCommand> commands = new ArrayList<>();
        List<ChatRequestEntity> requests = new ArrayList<>();
        AtomicInteger calls = new AtomicInteger();
        AbstractInvoker invoker = fixture.invoker(request -> {
            requests.add(request);
            if (calls.incrementAndGet() == 1) {
                fixture.execution.getMessages().add(UserMessageEntity.from("later input"));
                fixture.registry.register(ToolDefinition.builder().id("later-tool")
                        .name("later-tool").timeout(5L).maxOutput(100)
                        .executor(execution -> ToolExecuteResult.success("ok")).build());
            }
            if (calls.get() < 3) throw new CompletionException(new RetriableModelException("temporary", null));
            return response();
        });

        ChatResponseEntity result = invoker.invoke(fixture.attempts(commands));

        assertEquals(3, calls.get());
        assertEquals(3, commands.size());
        assertEquals(List.of("1", "2", "3"), commands.stream().map(ModelChatCommand::responseId).toList());
        assertEquals("3", result.getResponseId());
        assertEquals("3", fixture.execution.getLastResponseId());
        assertEquals(2, fixture.execution.getMessages().size());
        for (int index = 0; index < commands.size(); index++) {
            assertEquals("execution", commands.get(index).executionId());
            assertEquals(1, requests.get(index).getMessages().size());
            assertEquals("original input", requests.get(index).getMessages().getFirst().text());
            assertEquals(0, requests.get(index).getTools().size());
            assertSame(requests.getFirst().getMessages(), requests.get(index).getMessages());
            assertSame(requests.getFirst().getTools(), requests.get(index).getTools());
        }
        if (streaming) {
            StreamingModelResponseBehaveDecider first = handler(commands.get(0));
            StreamingModelResponseBehaveDecider second = handler(commands.get(1));
            StreamingModelResponseBehaveDecider third = handler(commands.get(2));
            assertNotSame(first, second);
            assertNotSame(second, third);
            assertNotSame(first.getStreamingResponseContext().future(), second.getStreamingResponseContext().future());
            assertNotSame(second.getStreamingResponseContext().future(), third.getStreamingResponseContext().future());
            assertEquals("1", first.getStreamingResponseContext().responseId());
            assertEquals("2", second.getStreamingResponseContext().responseId());
            assertEquals("3", third.getStreamingResponseContext().responseId());
        }
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void zeroRetriesDoesNotConstructAnotherAttempt(boolean streaming) {
        Fixture fixture = new Fixture(streaming, retrySettings(0));
        List<ModelChatCommand> commands = new ArrayList<>();
        AtomicInteger calls = new AtomicInteger();
        AbstractInvoker invoker = fixture.invoker(request -> {
            calls.incrementAndGet();
            throw new CompletionException(new RetriableModelException("temporary", null));
        });

        assertThrows(RuntimeException.class, () -> invoker.invoke(fixture.attempts(commands)));
        assertEquals(1, calls.get());
        assertEquals(1, commands.size());
        assertEquals("1", fixture.execution.getLastResponseId());
    }

    @Test
    void factoryBindsRuntimeTaskToRequestSettings() throws Exception {
        ModelRetryConfig settings = ModelRetryConfig.builder().enabled(true).maxRetries(2)
                .initialDelay(Duration.ofMillis(37)).multiplier(3).maxDelay(Duration.ofMillis(140)).build();
        Fixture fixture = new Fixture(false, settings);
        ModelChatCommand command = fixture.factory.build(fixture.execution, List.of(), "1",
                new ExecutionControlSignal(fixture.execution.getId()));
        AtomicInteger calls = new AtomicInteger();
        RetryUtil.Task<ChatResponseEntity> attempt = () -> {
            calls.incrementAndGet();
            return response();
        };
        RetryUtil.RetryConf<ChatResponseEntity> assembled = ModelRequestFactory.buildRetryConfig(command,
                () -> attempt, () -> command);

        assertEquals(37, assembled.basicTimeout());
        assertEquals(2, assembled.maxRetry());
        assertEquals(3, assembled.multiple());
        assertEquals(140, assembled.maxDelay());
        assertEquals(TimeUnit.MILLISECONDS, assembled.timeUnit());
        RetryUtil.retry(assembled);
        assertEquals(1, calls.get());
    }

    private static ModelRetryConfig retrySettings(int retries) {
        return ModelRetryConfig.builder().enabled(true).maxRetries(retries)
                .initialDelay(Duration.ZERO).maxDelay(Duration.ZERO).build();
    }

    private static ChatResponseEntity response() {
        return ChatResponseEntity.builder().aiMessageEntity(AiMessageEntity.builder().text("answer").build())
                .tokenUsage(TokenUsageEntity.empty()).build();
    }

    private static StreamingModelResponseBehaveDecider handler(ModelChatCommand command) {
        return (StreamingModelResponseBehaveDecider) command.streamingChatResponseHandler();
    }

    private static final class Fixture {
        private final Execution execution;
        private final ToolRegistry registry = new ToolRegistry(List.of());
        private final ModelRequestFactory factory;

        private Fixture(boolean streaming, ModelRetryConfig retryConfig) {
            AgentRequest request = AgentRequest.builder().messages(List.of(UserMessageEntity.from("original input")))
                    .retryConfig(retryConfig).build();
            execution = Execution.create(request, "agent");
            execution.setId("execution");
            execution.setStreaming(streaming);
            AtomicInteger ids = new AtomicInteger();
            ToolExecutionManager tools = new ToolExecutionManager() {
                public List<ToolExecuteResult> execute(ToolExecuteCommand command) { return List.of(); }
                public ToolRegistry toolRegistry() { return registry; }
            };
            factory = new ModelRequestFactory(RuntimeContext.builder()
                    .conversationManager(new DefaultConversationManager(ContextAttachmentProvider.NONE))
                    .toolExecutionManager(tools).runtimeEventPublisher(new RuntimeEventPublisher(List.of()))
                    .runtimeBoundaryChecker(new BoundaryChecker(AgentConfig.builder().maxIterations(10).build(),
                            new DefaultTokenizer(), new DefaultConversationManager(ContextAttachmentProvider.NONE), null, null))
                    .responseIdGenerator(previous -> Integer.toString(ids.incrementAndGet())).build());
        }

        private Supplier<ModelChatCommand> attempts(List<ModelChatCommand> commands) {
            Supplier<ModelChatCommand> attempts = factory.buildAttempts(execution, List.of("later-tool"),
                    new ExecutionControlSignal(execution.getId()), new ModelInvocationUsage());
            return () -> {
                ModelChatCommand command = attempts.get();
                commands.add(command);
                return command;
            };
        }

        private AbstractInvoker invoker(Function<ChatRequestEntity, ChatResponseEntity> behavior) {
            return execution.isStreaming()
                    ? new DefaultStreamingModelInvoker((request, handler) -> handler.onFinalResponse(behavior.apply(request)))
                    : new DefaultChatModelInvoker(behavior::apply);
        }
    }
}
