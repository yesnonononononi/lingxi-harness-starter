package com.summit.runtime.model;

import com.summit.core.agent.AgentRequest;
import com.summit.core.exception.RetriableModelException;
import com.summit.core.agent.Execution;
import com.summit.core.agent.ExecutionState;
import com.summit.core.compact.ContextAttachmentProvider;
import com.summit.core.conf.ModelRetryConfig;
import com.summit.core.conversation.api.ChatResponseEntity;
import com.summit.core.conversation.event.AgentCompleteTextEvent;
import com.summit.core.conversation.event.ExecutionCancelledEvent;
import com.summit.core.conversation.event.ExecutionCompleteEvent;
import com.summit.core.conversation.event.ExecutionErrorEvent;
import com.summit.core.conversation.event.RuntimeEventPublisher;
import com.summit.core.conversation.event.InvokeRetryEvent;
import com.summit.core.conversation.event.AgentPartialTextEvent;
import com.summit.core.conversation.event.TokenInfo;
import com.summit.core.conversation.message.AiMessageEntity;
import com.summit.core.conversation.message.TokenUsageEntity;
import com.summit.core.conversation.message.UserMessageEntity;
import com.summit.core.model.ModelChatCommand;
import com.summit.core.model.ModelInvoker;
import com.summit.core.model.chat.ChatModel;
import com.summit.core.model.streaming.StreamingChatModel;
import com.summit.core.model.streaming.StreamingChatResponseHandler;
import com.summit.core.model.streaming.StreamingHandler;
import com.summit.core.runtime.RuntimeListener;
import com.summit.core.runtime.loop.ContextUsageReporter;
import com.summit.core.tool.ToolExecuteCommand;
import com.summit.core.tool.ToolExecuteResult;
import com.summit.core.tool.ToolExecutionManager;
import com.summit.core.tool.ToolRegistry;
import com.summit.runtime.agent.AgentConfig;
import com.summit.runtime.context.RuntimeContext;
import com.summit.runtime.conversation.DefaultConversationManager;
import com.summit.runtime.conversation.DefaultTokenizer;
import com.summit.runtime.loop.BoundaryChecker;
import com.summit.runtime.loop.DefaultExecutionController;
import com.summit.runtime.loop.DefaultLoopInterceptor;
import com.summit.runtime.loop.DefaultLoopInterceptorProcessor;
import com.summit.runtime.loop.DefaultRuntimeLifeStyleManager;
import com.summit.runtime.loop.RuntimeProcessorTemplate;
import com.summit.runtime.loop.control.InMemoryActiveExecutionRegistry;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.io.IOException;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletionException;
import java.util.concurrent.CancellationException;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;

import static org.junit.jupiter.api.Assertions.*;

class ModelInvocationUsageIntegrationTest {
    @Test
    void threadInterruptionAtBackoffCancelsExecutionAndPreservesTheFlag() {
        Fixture fixture = new Fixture();
        fixture.enableRetries(2);
        fixture.retryNotice = event -> Thread.currentThread().interrupt();
        try {
            assertDoesNotThrow(() -> fixture.runStream((request, handler) ->
                    handler.onError(new RetriableModelException("temporary", null))));
            assertEquals(ExecutionState.CANCELLED, fixture.execution.getExecutionState());
            assertTrue(Thread.currentThread().isInterrupted());
            assertEquals(1, fixture.execution.getModelAttempts());
        } finally {
            Thread.interrupted();
        }
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void futureCancellationSealsTheStreamAndDoesNotRetry(boolean wrapped) {
        Fixture fixture = new Fixture();
        fixture.enableRetries(2);
        AtomicInteger cancellations = new AtomicInteger();
        assertDoesNotThrow(() -> fixture.runStream((request, handler) -> {
            handler.onPartialResponse("before", transport(cancellations));
            if (wrapped) handler.onError(new CompletionException(new CancellationException("cancelled")));
            else ((StreamingModelResponseBehaveDecider) handler).getStreamingResponseContext().future().cancel(false);
        }));
        assertEquals(ExecutionState.CANCELLED, fixture.execution.getExecutionState());
        assertEquals(1, fixture.execution.getModelAttempts());
        assertEquals(1, cancellations.get());
    }
    @Test
    void startupFailureSealsTheOldAttemptAndCancelsItsTransportBeforeRetrying() {
        Fixture fixture = new Fixture();
        fixture.enableRetries(1);
        List<StreamingChatResponseHandler> handlers = new ArrayList<>();
        AtomicInteger cancellations = new AtomicInteger();
        StreamingHandler transport = transport(cancellations);
        fixture.runStream((request, handler) -> {
            handlers.add(handler);
            if (handlers.size() == 1) {
                handler.onPartialResponse("before", transport);
                throw new RetriableModelException("startup failed", null);
            }
            assertEquals(1, cancellations.get());
            handler.onFinalResponse(response(10));
        });
        handlers.getFirst().onPartialResponse("late", transport);
        handlers.getFirst().onFinalResponse(response(999));
        assertEquals(List.of("before"), fixture.text);
        assertTrue(((StreamingModelResponseBehaveDecider) handlers.getFirst()).getStreamingResponseContext()
                .future().isCompletedExceptionally());
        assertEquals(2, fixture.execution.getModelAttempts());
        assertEquals(10, fixture.execution.getTokenUsage().getTotalTokens());
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void idleStreamObservesControlWithoutAnotherProviderCallback(boolean cancel) throws Exception {
        Fixture fixture = new Fixture();
        CountDownLatch started = new CountDownLatch(1);
        AtomicInteger cancellations = new AtomicInteger();
        try (ExecutorService executor = Executors.newSingleThreadExecutor()) {
            Future<?> run = executor.submit(() -> fixture.runStream((request, handler) -> {
                handler.onPartialResponse("before", transport(cancellations));
                started.countDown();
            }));
            assertTrue(started.await(5, TimeUnit.SECONDS));
            if (cancel) fixture.repository.requireCancel("execution");
            else fixture.repository.requireSuspend("execution");
            try {
                assertDoesNotThrow(() -> run.get(2, TimeUnit.SECONDS));
            } finally {
                run.cancel(true);
            }
        }
        assertEquals(cancel ? ExecutionState.CANCELLED : ExecutionState.SUSPENDED, fixture.execution.getExecutionState());
        assertEquals(1, cancellations.get());
    }

    @Test
    void interruptedWaitClosesTheStreamAndRetainsTheThreadFlag() throws Exception {
        Fixture fixture = new Fixture();
        CountDownLatch started = new CountDownLatch(1);
        AtomicInteger cancellations = new AtomicInteger();
        AtomicBoolean interrupted = new AtomicBoolean();
        AtomicReference<Throwable> failure = new AtomicReference<>();
        Thread worker = new Thread(() -> {
            try {
                fixture.runStream((request, handler) -> {
                    handler.onPartialResponse("before", transport(cancellations));
                    started.countDown();
                });
            } catch (Throwable error) {
                failure.set(error);
            } finally {
                interrupted.set(Thread.currentThread().isInterrupted());
            }
        });
        worker.setDaemon(true);
        worker.start();
        assertTrue(started.await(5, TimeUnit.SECONDS));
        worker.interrupt();
        worker.join(2_000);
        assertFalse(worker.isAlive());
        assertNull(failure.get());
        assertTrue(interrupted.get());
        assertEquals(ExecutionState.CANCELLED, fixture.execution.getExecutionState());
        assertEquals(1, cancellations.get());
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void backoffControlPreservesCancellationAndSuspensionWithoutAnotherRequest(boolean cancel) {
        Fixture fixture = new Fixture();
        fixture.execution.getAgentRequest().setRetryConfig(ModelRetryConfig.builder().enabled(true)
                .initialDelay(Duration.ofSeconds(30)).maxDelay(Duration.ofSeconds(30)).build());
        AtomicInteger calls = new AtomicInteger();
        fixture.retryNotice = event -> {
            assertEquals(1, calls.get());
            if (cancel) fixture.repository.requireCancel("execution");
            else fixture.repository.requireSuspend("execution");
        };
        fixture.runStream((request, handler) -> {
            calls.incrementAndGet();
            handler.onError(new RetriableModelException("temporary", null));
        });
        assertEquals(cancel ? ExecutionState.CANCELLED : ExecutionState.SUSPENDED, fixture.execution.getExecutionState());
        assertEquals(1, calls.get());
        assertEquals(1, fixture.execution.getModelAttempts());
    }

    @Test
    void retriesCannotExceedTheExecutionAttemptBudget() {
        Fixture fixture = new Fixture();
        fixture.enableRetries(3);
        fixture.execution.setMaxSteps(1);
        AtomicInteger calls = new AtomicInteger();
        assertThrows(RuntimeException.class, () -> fixture.runStream((request, handler) -> {
            calls.incrementAndGet();
            handler.onError(new RetriableModelException("temporary", null));
        }));
        assertEquals(1, calls.get());
        assertEquals(1, fixture.execution.getModelAttempts());
        assertEquals(ExecutionState.FAILED, fixture.execution.getExecutionState());
    }

    @Test
    void applicationPublicationFailuresDoNotReplayAnAlreadyReceivedModelResponse() {
        Fixture fixture = new Fixture();
        fixture.enableRetries(2);
        AtomicInteger calls = new AtomicInteger();
        fixture.publication = event -> { throw new CompletionException(new IllegalStateException("publication failed")); };
        assertThrows(RuntimeException.class, () -> fixture.runStream((request, handler) -> {
            calls.incrementAndGet();
            handler.onFinalResponse(response(10));
        }));
        assertEquals(1, calls.get());
        assertEquals(10, fixture.execution.getTokenUsage().getTotalTokens());
    }

    @Test
    void retryNotificationsIdentifyEachFailedAttemptAndCarryExecutionMetadata() {
        Fixture fixture = new Fixture();
        fixture.enableRetries(2);
        fixture.execution.getAgentRequest().runtimeParametersOrDefault().setEventMetaData(Map.of("turnId", "900"));
        List<InvokeRetryEvent> notices = new ArrayList<>();
        AtomicInteger calls = new AtomicInteger();
        fixture.retryNotice = event -> {
            assertEquals(calls.get(), event.getRetryCount());
            notices.add(event);
        };
        fixture.runStream((request, handler) -> {
            if (calls.incrementAndGet() < 3) handler.onError(new RetriableModelException("temporary", null));
            else handler.onFinalResponse(response(10));
        });
        assertEquals(List.of("1", "2"), notices.stream().map(InvokeRetryEvent::getResponseId).toList());
        for (InvokeRetryEvent notice : notices) {
            assertEquals("execution", notice.executionId());
            assertEquals(Map.of("turnId", "900"), notice.eventMetaData());
            assertEquals(TimeUnit.MILLISECONDS, notice.getTimeUnit());
            assertEquals(0, notice.getRetryDelay());
            assertNotNull(notice.timestamp());
        }
        assertEquals(3, fixture.execution.getModelAttempts());
    }

    private static StreamingHandler transport(AtomicInteger cancellations) {
        return new StreamingHandler() {
            public void cancel() { cancellations.incrementAndGet(); }
            public boolean isCancelled() { return cancellations.get() > 0; }
        };
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void retriesWithUnreportedUsageCountOnlyTheReceivedResponse(boolean streaming) {
        Fixture fixture = new Fixture();
        fixture.enableRetries(1);
        AtomicInteger calls = new AtomicInteger();
        ChatResponseEntity answer = response(10);
        if (streaming) {
            fixture.runStream((request, handler) -> {
                if (calls.incrementAndGet() == 1) handler.onError(new RetriableModelException("connection closed", null));
                else handler.onFinalResponse(answer);
            });
        } else {
            fixture.runSync(request -> {
                if (calls.incrementAndGet() == 1) throw new CompletionException(new RetriableModelException("connection closed", null));
                return answer;
            });
        }

        assertEquals(2, calls.get());
        assertEquals(ExecutionState.COMPLETED, fixture.execution.getExecutionState());
        assertEquals(10, fixture.execution.getTokenUsage().getTotalTokens());
        assertEquals(10, fixture.terminalUsage.get().totalTokenCount());
        assertEquals(10, answer.getTokenUsage().getTotalTokens());
        assertEquals(List.of("1", "2"), fixture.commands.stream().map(ModelChatCommand::responseId).toList());
        assertSame(fixture.commands.getFirst().invocationUsage(), fixture.commands.getLast().invocationUsage());
    }

    @Test
    void publicationFailuresRetainEachAttemptUsageWithoutChangingTheSuccessfulResponse() {
        Fixture fixture = new Fixture();
        fixture.enableRetries(2);
        AtomicInteger publications = new AtomicInteger();
        AtomicInteger calls = new AtomicInteger();
        AtomicReference<ChatResponseEntity> lastResponse = new AtomicReference<>();
        fixture.publication = event -> {
            assertEquals(0, fixture.execution.getTokenUsage().getTotalTokens());
            if (publications.incrementAndGet() < 3) throw publicationFailure();
        };

        fixture.runStream((request, handler) -> {
            ChatResponseEntity answer = response(calls.incrementAndGet() * 10);
            lastResponse.set(answer);
            handler.onFinalResponse(answer);
        });

        assertEquals(3, calls.get());
        assertEquals(60, fixture.execution.getTokenUsage().getTotalTokens());
        assertEquals(60, fixture.terminalUsage.get().totalTokenCount());
        assertEquals(30, lastResponse.get().getTokenUsage().getTotalTokens());
        assertNull(fixture.commands.getFirst().invocationUsage().finish());
    }

    @Test
    void exhaustedRetriesStillSettleCollectedUsageBeforeFailureNotification() {
        Fixture fixture = new Fixture();
        fixture.enableRetries(1);
        fixture.publication = event -> { throw publicationFailure(); };
        AtomicInteger calls = new AtomicInteger();

        assertThrows(RuntimeException.class, () -> fixture.runStream((request, handler) ->
                handler.onFinalResponse(response(calls.incrementAndGet() * 10))));

        assertEquals(2, calls.get());
        assertEquals(ExecutionState.FAILED, fixture.execution.getExecutionState());
        assertEquals(30, fixture.execution.getTokenUsage().getTotalTokens());
        assertEquals(30, fixture.terminalUsage.get().totalTokenCount());
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void aSingleAttemptPublicationFailureAlsoSettlesUsage(boolean retryEnabled) {
        Fixture fixture = new Fixture();
        if (retryEnabled) fixture.enableRetries(0);
        fixture.publication = event -> { throw publicationFailure(); };

        assertThrows(RuntimeException.class, () -> fixture.runStream((request, handler) ->
                handler.onFinalResponse(response(10))));

        assertEquals(1, fixture.commands.size());
        assertEquals(10, fixture.execution.getTokenUsage().getTotalTokens());
        assertEquals(10, fixture.terminalUsage.get().totalTokenCount());
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void receivedFinalUsageSurvivesCancellationOrSuspensionBeforeDelivery(boolean cancel) {
        Fixture fixture = new Fixture();
        AtomicInteger publications = new AtomicInteger();
        fixture.publication = event -> publications.incrementAndGet();

        fixture.runStream((request, handler) -> {
            if (cancel) fixture.repository.requireCancel("execution");
            else fixture.repository.requireSuspend("execution");
            handler.onFinalResponse(response(10));
        });

        assertEquals(cancel ? ExecutionState.CANCELLED : ExecutionState.SUSPENDED, fixture.execution.getExecutionState());
        assertEquals(10, fixture.execution.getTokenUsage().getTotalTokens());
        assertEquals(0, publications.get());
        if (cancel) assertEquals(10, fixture.terminalUsage.get().totalTokenCount());
        else assertEquals(10, fixture.repository.findById("execution").orElseThrow().getTokenUsage().getTotalTokens());
    }

    @Test
    void lateCallbacksFromTheFailedAttemptCannotChangeTheNewAttemptUsage() {
        Fixture fixture = new Fixture();
        fixture.enableRetries(1);
        List<StreamingChatResponseHandler> handlers = new ArrayList<>();

        fixture.runStream((request, handler) -> {
            handlers.add(handler);
            if (handlers.size() == 1) handler.onError(new RetriableModelException("connection closed", null));
            else {
                handlers.getFirst().onFinalResponse(response(999));
                handler.onFinalResponse(response(10));
                handler.onFinalResponse(response(999));
            }
        });
        handlers.getFirst().onFinalResponse(response(999));
        handlers.getLast().onFinalResponse(response(999));

        assertEquals(10, fixture.execution.getTokenUsage().getTotalTokens());
        assertEquals(10, fixture.terminalUsage.get().totalTokenCount());
    }

    @Test
    void suspensionRestoreCreatesANewScopeAndPreservesAlreadySettledUsage() {
        Fixture fixture = new Fixture();
        fixture.runStream((request, handler) -> {
            fixture.repository.requireSuspend("execution");
            handler.onFinalResponse(response(10));
        });
        ModelChatCommand first = fixture.commands.getFirst();
        fixture.execution = fixture.repository.findById("execution").orElseThrow();

        fixture.runStream((request, handler) -> handler.onFinalResponse(response(20)));

        assertEquals(ExecutionState.COMPLETED, fixture.execution.getExecutionState());
        assertEquals(30, fixture.execution.getTokenUsage().getTotalTokens());
        assertEquals(30, fixture.terminalUsage.get().totalTokenCount());
        assertNotSame(first.invocationUsage(), fixture.commands.getLast().invocationUsage());
        assertEquals(List.of("1", "2"), fixture.commands.stream().map(ModelChatCommand::responseId).toList());
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void missingUsageIsNotWrittenBackAsAZeroValuedResponse(boolean streaming) {
        Fixture fixture = new Fixture();
        ChatResponseEntity answer = response(0);
        answer.setTokenUsage(null);
        if (streaming) fixture.runStream((request, handler) -> handler.onFinalResponse(answer));
        else fixture.runSync(request -> answer);
        assertNull(answer.getTokenUsage());
        assertEquals(0, fixture.execution.getTokenUsage().getTotalTokens());
    }

    private static CompletionException publicationFailure() {
        return new CompletionException(new RetriableModelException("completion boundary failed", null));
    }

    private static ChatResponseEntity response(int totalTokens) {
        return ChatResponseEntity.builder().aiMessageEntity(AiMessageEntity.builder().text("answer").build())
                .tokenUsage(TokenUsageEntity.of(totalTokens, totalTokens * 7 / 10, totalTokens * 3 / 10)).build();
    }

    private static final class Fixture {
        private final InMemoryActiveExecutionRegistry repository = new InMemoryActiveExecutionRegistry();
        private final List<ModelChatCommand> commands = new ArrayList<>();
        private final AtomicReference<TokenInfo> terminalUsage = new AtomicReference<>();
        private final List<String> text = new ArrayList<>();
        private Consumer<InvokeRetryEvent> retryNotice = event -> { };
        private Consumer<AgentCompleteTextEvent> publication = event -> { };
        private Execution execution = Execution.builder().id("execution").agentId("agent")
                .executionState(ExecutionState.CREATED)
                .agentRequest(AgentRequest.builder().messages(List.of(UserMessageEntity.from("question"))).build())
                .messages(new ArrayList<>(List.of(UserMessageEntity.from("question"))))
                .tokenUsage(TokenUsageEntity.empty()).build();

        private void enableRetries(int retries) {
            execution.getAgentRequest().setRetryConfig(ModelRetryConfig.builder().enabled(true).maxRetries(retries)
                    .initialDelay(Duration.ZERO).maxDelay(Duration.ZERO).build());
        }

        private void runSync(ChatModel model) { execute(new DefaultChatModelInvoker(model)); }

        private void runStream(StreamingChatModel model) {
            execution.setStreaming(true);
            execute(new DefaultStreamingModelInvoker(model));
        }

        private void execute(ModelInvoker invoker) {
            RuntimeListener listener = new RuntimeListener() {
                public void onInvokeRetry(InvokeRetryEvent event) { retryNotice.accept(event); }
                public void onPartialText(AgentPartialTextEvent event) { text.add(event.content()); }
                public void onExecutionCompleted(ExecutionCompleteEvent event) { terminalUsage.set(event.getTokenInfo()); }
                public void onExecutionError(ExecutionErrorEvent event) { terminalUsage.set(event.getTokenInfo()); }
                public void onExecutionCancelled(ExecutionCancelledEvent event) { terminalUsage.set(event.getTokenInfo()); }
            };
            RuntimeEventPublisher events = new RuntimeEventPublisher(List.of(listener)) {
                public void onCompleteText(AgentCompleteTextEvent event) {
                    publication.accept(event);
                    super.onCompleteText(event);
                }
            };
            DefaultRuntimeLifeStyleManager lifecycle = new DefaultRuntimeLifeStyleManager(events);
            DefaultTokenizer tokenizer = new DefaultTokenizer();
            DefaultConversationManager conversations = new DefaultConversationManager(ContextAttachmentProvider.NONE);
            ToolExecutionManager tools = new ToolExecutionManager() {
                public List<ToolExecuteResult> execute(ToolExecuteCommand command) { return List.of(); }
                public ToolRegistry toolRegistry() { return new ToolRegistry(List.of()); }
            };
            RuntimeContext context = RuntimeContext.builder().conversationManager(conversations)
                    .executionRepository(repository)
                    .executionControl(new DefaultExecutionController(() -> null, repository, events, lifecycle))
                    .loopInterceptorProcessor(new DefaultLoopInterceptorProcessor(List.of(new DefaultLoopInterceptor())))
                    .runtimeLifeStyleManager(lifecycle)
                    .runtimeBoundaryChecker(new BoundaryChecker(AgentConfig.builder().maxIterations(4).build(),
                            tokenizer, conversations, null, null))
                    .usage(new ContextUsageReporter(tokenizer, 1_024_000, events, 1))
                    .runtimeEventPublisher(events).toolExecutionManager(tools)
                    .responseIdGenerator(previous -> Integer.toString(previous == null ? 1 : Integer.parseInt(previous) + 1))
                    .invoker(attempts -> invoker.invoke(() -> {
                        ModelChatCommand command = attempts.get();
                        commands.add(command);
                        return command;
                    })).build();
            new RuntimeProcessorTemplate(context).execute(execution);
        }
    }
}
