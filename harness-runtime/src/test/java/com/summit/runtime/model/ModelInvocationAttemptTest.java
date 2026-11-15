package com.summit.runtime.model;

import com.summit.core.agent.AgentRequest;
import com.summit.core.agent.ContextSpec;
import com.summit.core.agent.Execution;
import com.summit.core.compact.ContextAttachmentProvider;
import com.summit.core.conf.ModelRetryConfig;
import com.summit.core.conversation.api.ChatRequestEntity;
import com.summit.core.conversation.api.ChatResponseEntity;
import com.summit.core.conversation.event.InvokeRetryEvent;
import com.summit.core.conversation.event.RuntimeEventPublisher;
import com.summit.core.conversation.message.AiMessageEntity;
import com.summit.core.conversation.message.TokenUsageEntity;
import com.summit.core.conversation.message.UserMessageEntity;
import com.summit.core.exception.ResponseTimeoutException;
import com.summit.core.model.ModelChatCommand;
import com.summit.core.model.ModelInvocationUsage;
import com.summit.core.model.image.ImageGenerationOptions;
import com.summit.core.model.image.ImageModel;
import com.summit.core.model.streaming.StreamingHandler;
import com.summit.core.exception.RetriableModelException;
import com.summit.core.runtime.RuntimeListener;
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
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Function;
import java.util.function.Supplier;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ModelInvocationAttemptTest {
    @Test
    void imageAttemptsKeepTheOriginalSpecificationsThroughRetriesAndRequestMutation() throws Exception {
        Fixture fixture = new Fixture(false, retrySettings(1));
        var original = new ImageGenerationOptions("1536x1024", "high", 2);
        fixture.execution.getAgentRequest().setImageGenerationOptions(original);
        List<ModelChatCommand> commands = new ArrayList<>();
        AtomicInteger calls = new AtomicInteger();
        DefaultImageModelInvoker invoker = new DefaultImageModelInvoker(new ImageModel() {
            public ChatResponseEntity generate(String prompt) { throw new AssertionError("specifications required"); }
            public ChatResponseEntity generate(String prompt, ImageGenerationOptions options) {
                assertEquals("original input", prompt);
                assertEquals(original, options);
                if (calls.incrementAndGet() == 1) {
                    fixture.execution.getAgentRequest().setImageGenerationOptions(new ImageGenerationOptions("1024x1024", null, 1));
                    throw new RetriableModelException("temporary", null);
                }
                return response();
            }
        });
        assertEquals("2", invoker.invoke(fixture.attempts(commands)).getResponseId());
        assertEquals(2, calls.get());
        for (ModelChatCommand command : commands) assertEquals(original, command.chatRequest().getImageGenerationOptions());
        // The next round sees the changed request; retries of the previous round did not.
        var next = fixture.factory.build(fixture.execution, List.of(), "3", null);
        assertEquals("1024x1024", next.chatRequest().getImageGenerationOptions().size());
    }

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

    @Test
    void streamingIdleTimeoutTriggersRetryAndDisposesResources() throws Exception {
        List<InvokeRetryEvent> retryEvents = new ArrayList<>();
        RuntimeListener listener = new RuntimeListener() {
            @Override
            public int order() { return 0; }

            @Override
            public void onInvokeRetry(InvokeRetryEvent event) {
                retryEvents.add(event);
            }
        };
        Fixture fixture = new Fixture(true, retrySettings(1), listener);
        List<ModelChatCommand> commands = new ArrayList<>();
        List<RecordingTransport> transports = new ArrayList<>();
        AtomicInteger calls = new AtomicInteger();

        DefaultStreamingModelInvoker invoker = new DefaultStreamingModelInvoker((request, handler) -> {
            RecordingTransport transport = new RecordingTransport();
            transports.add(transport);
            int attempt = calls.incrementAndGet();
            handler.onStreamStart(transport);
            if (attempt == 1) {
                handler.onPartialResponse("partial-before-stall", transport);
                return;
            }
            handler.onFinalResponse(response());
        });

        ChatResponseEntity result = invoker.invoke(
                streamingAttemptsWithTimeout(fixture, commands, Duration.ofMillis(80), Duration.ofMillis(20)));

        assertEquals(2, calls.get());
        assertEquals("2", result.getResponseId());
        assertEquals(1, retryEvents.size());
        assertEquals("1", retryEvents.getFirst().getResponseId());

        assertTrue(transports.get(0).awaitCancel(2, TimeUnit.SECONDS));
        assertEquals(1, transports.get(0).cancelCount());
        assertEquals(0, transports.get(1).cancelCount());
        assertFalse(handler(commands.get(0)).getWatchDog().isRunning());
        assertFalse(handler(commands.get(1)).getWatchDog().isRunning());
    }

    @Test
    void streamingIdleTimeoutExhaustsRetriesAndDisposesAllAttemptResources() throws Exception {
        Fixture fixture = new Fixture(true, retrySettings(1));
        List<ModelChatCommand> commands = new ArrayList<>();
        List<RecordingTransport> transports = new ArrayList<>();
        AtomicInteger calls = new AtomicInteger();

        DefaultStreamingModelInvoker invoker = new DefaultStreamingModelInvoker((request, handler) -> {
            RecordingTransport transport = new RecordingTransport();
            transports.add(transport);
            calls.incrementAndGet();
            handler.onStreamStart(transport);
        });

        CompletionException thrown = assertThrows(CompletionException.class, () -> invoker.invoke(
                streamingAttemptsWithTimeout(fixture, commands, Duration.ofMillis(60), Duration.ofMillis(15))));

        assertInstanceOf(ResponseTimeoutException.class, thrown.getCause());
        assertEquals(2, calls.get());
        assertEquals(2, transports.size());
        for (int index = 0; index < transports.size(); index++) {
            assertTrue(transports.get(index).awaitCancel(2, TimeUnit.SECONDS));
            assertEquals(1, transports.get(index).cancelCount());
            assertFalse(handler(commands.get(index)).getWatchDog().isRunning());
        }
    }

    private static Supplier<ModelChatCommand> streamingAttemptsWithTimeout(
            Fixture fixture,
            List<ModelChatCommand> commands,
            Duration activeWindow,
            Duration heartbeatInterval) {
        Supplier<ModelChatCommand> base = fixture.factory.buildAttempts(
                fixture.execution, List.of("later-tool"),
                new ExecutionControlSignal(fixture.execution.getId()), new ModelInvocationUsage());
        return () -> {
            ModelChatCommand original = base.get();
            StreamingModelResponseBehaveDecider originalHandler =
                    (StreamingModelResponseBehaveDecider) original.streamingChatResponseHandler();
            StreamingModelResponseBehaveDecider customHandler = new StreamingModelResponseBehaveDecider(
                    original.runtimeEventPublisher(),
                    StreamingModelResponseBehaveDecider.StreamingResponseContext.builder()
                            .executionId(originalHandler.getStreamingResponseContext().executionId())
                            .agentId(originalHandler.getStreamingResponseContext().agentId())
                            .responseId(originalHandler.getStreamingResponseContext().responseId())
                            .eventMetaData(originalHandler.getStreamingResponseContext().eventMetaData())
                            .invocationUsage(originalHandler.getStreamingResponseContext().invocationUsage())
                            .future(new CompletableFuture<>())
                            .build(),
                    original.signal(),
                    activeWindow,
                    heartbeatInterval);
            ModelChatCommand wrapped = ModelChatCommand.builder()
                    .executionId(original.executionId())
                    .responseId(original.responseId())
                    .chatRequest(original.chatRequest())
                    .signal(original.signal())
                    .streamingChatResponseHandler(customHandler)
                    .streaming(original.streaming())
                    .thinking(original.thinking())
                    .runtimeEventPublisher(original.runtimeEventPublisher())
                    .retryConfig(original.retryConfig())
                    .invocationUsage(original.invocationUsage())
                    .beforeAttempt(original.beforeAttempt())
                    .eventMetaData(original.eventMetaData())
                    .build();
            commands.add(wrapped);
            return wrapped;
        };
    }

    private static final class RecordingTransport implements StreamingHandler {
        private final AtomicInteger cancelCount = new AtomicInteger();
        private final CountDownLatch cancelled = new CountDownLatch(1);

        @Override
        public void cancel() {
            cancelCount.incrementAndGet();
            cancelled.countDown();
        }

        @Override
        public boolean isCancelled() {
            return cancelCount.get() > 0;
        }

        boolean awaitCancel(long timeout, TimeUnit unit) throws InterruptedException {
            return cancelled.await(timeout, unit);
        }

        int cancelCount() {
            return cancelCount.get();
        }
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

        private Fixture(boolean streaming, ModelRetryConfig retryConfig, RuntimeListener... listeners) {
            AgentRequest request = AgentRequest.builder()
                    .contextSpec(ContextSpec.builder()
                            .context(List.of(UserMessageEntity.from("original input")))
                            .build())
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
                    .toolExecutionManager(tools).runtimeEventPublisher(new RuntimeEventPublisher(List.of(listeners)))
                    .runtimeBoundaryChecker(new BoundaryChecker(AgentConfig.builder().maxIterations(10).build(),
                            new DefaultTokenizer(), new DefaultConversationManager(ContextAttachmentProvider.NONE), null, null, null, null))
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
