package com.summit.runtime.loop;

import com.summit.core.agent.*;
import com.summit.core.compact.ContextAttachmentProvider;
import com.summit.core.conf.SkillConfig;
import com.summit.core.exception.MaxStepsExceededException;
import com.summit.core.conversation.api.*;
import com.summit.runtime.context.RuntimeContext;
import com.summit.core.conversation.event.*;
import com.summit.core.conversation.message.*;
import com.summit.core.model.ModelInvoker;
import com.summit.core.runtime.RuntimeListener;
import com.summit.core.runtime.loop.*;
import com.summit.core.runtime.loop.lifestyle.RuntimeLifeStyleManager;
import com.summit.core.tool.*;
import com.summit.runtime.agent.AgentConfig;
import com.summit.runtime.conversation.*;
import com.summit.runtime.loop.control.InMemoryActiveExecutionRegistry;
import org.junit.jupiter.api.Test;

import java.util.*;
import java.nio.file.Path;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;

class LoopRegressionTest {
    private final InMemoryActiveExecutionRegistry repository = new InMemoryActiveExecutionRegistry();
    private final DefaultTokenizer tokenizer = new DefaultTokenizer();
    private final AtomicInteger transcripts = new AtomicInteger();
    private final DefaultConversationManager conversations = new DefaultConversationManager(
            (id, ai, tools) -> transcripts.incrementAndGet(),
            ContextAttachmentProvider.NONE);

    @Test
    void failureNotificationOccursOnceAfterUsageAndRunEnd() {
        Execution execution = execution();
        List<String> notifications = new ArrayList<>();
        IllegalStateException failure = new IllegalStateException("model failed");
        LoopInterceptor interceptor = new LoopInterceptor() {
            public int order() { return 0; }
            public InterceptorResult onRunEnd(Execution finished) {
                notifications.add("run-end");
                return InterceptorResult.NONE;
            }
        };
        RuntimeListener listener = new RuntimeListener() {
            public void onContextUpdate(ContextUpdateEvent event) { notifications.add("usage"); }
            public void onExecutionError(ExecutionErrorEvent event) {
                assertEquals(ExecutionState.FAILED, execution.getExecutionState());
                assertTrue(repository.findById(execution.getId()).isEmpty());
                notifications.add("failed");
            }
        };

        assertSame(failure, assertThrows(IllegalStateException.class,
                () -> runtime(command -> { throw failure; }, List.of(), interceptor, 1, listener).execute(execution)));

        assertEquals(1, Collections.frequency(notifications, "failed"));
        assertEquals("failed", notifications.getLast());
        assertTrue(notifications.indexOf("usage") < notifications.indexOf("failed"));
        assertTrue(notifications.indexOf("run-end") < notifications.indexOf("failed"));
        ExecutionControlSignal nextRun = repository.register(execution.getId());
        repository.unregister(nextRun);
    }

    private Execution execution() {
        List<Message> messages = List.of(UserMessageEntity.from("task"));
        return Execution.builder().id("e").agentId("a").executionState(ExecutionState.CREATED)
                .agentRequest(AgentRequest.builder().messages(messages).build())
                .messages(new ArrayList<>(messages)).tokenUsage(TokenUsageEntity.empty()).build();
    }

    @Test
    void requestSkillConfigurationReachesToolBatch() throws Exception {
        Execution execution = execution();
        SkillConfig skills = new SkillConfig(Path.of("skills").toAbsolutePath());
        execution.getAgentRequest().setSkillConfig(skills);
        AtomicReference<ToolExecuteCommand> captured = new AtomicReference<>();
        ToolExecutionManager tools = new ToolExecutionManager() {
            public List<ToolExecuteResult> execute(ToolExecuteCommand command) {
                captured.set(command);
                return List.of(ToolExecuteResult.success("resource"));
            }
            public ToolRegistry toolRegistry() { return new ToolRegistry(List.of()); }
        };
        AtomicInteger rounds = new AtomicInteger();
        RuntimeEventPublisher events = new RuntimeEventPublisher(List.of());
        RuntimeContext context = RuntimeContext.builder().conversationManager(conversations)
                .toolExecutionManager(tools).runtimeEventPublisher(events)
                .loopInterceptorProcessor(new DefaultLoopInterceptorProcessor(List.of(new DefaultLoopInterceptor())))
                .runtimeBoundaryChecker(boundaryChecker(3))
                .usage(new ContextUsageReporter(tokenizer, 1_024_000, events, 1))
                .invoker(command -> response(rounds.getAndIncrement() == 0)).build();
        ExecutionControlSignal control = repository.register(execution.getId());
        try {
            assertEquals(LoopResult.Status.COMPLETED, new AgentLoopStepRunner(context).run(execution, control).status());
            assertEquals(skills.getPath(), captured.get().skillConfig().getPath());
        } finally {
            repository.unregister(control);
        }
    }

    @Test
    void aiMessageEventCarriesRequestMetadataThroughTheLoop() {
        Execution execution = execution();
        Map<String, Object> metadata = Map.of("turnId", "9007199254740995");
        execution.getAgentRequest().runtimeParametersOrDefault().setEventMetaData(metadata);
        AtomicReference<AgentMessageEvent> received = new AtomicReference<>();

        runtime(command -> response(false), List.of(), LoopInterceptor.NOOP, 1,
                new RuntimeListener() {
                    public void onAiMessage(AgentMessageEvent event) {
                        received.set(event);
                    }
                }).execute(execution);

        assertNotNull(received.get());
        assertEquals(metadata, received.get().eventMetaData());
    }
    private ChatResponseEntity response(boolean tools) {
        return ChatResponseEntity.builder().aiMessageEntity(AiMessageEntity.builder().text("answer")
                .toolCalls(tools ? List.of(new ToolCallRequest("c", "test", "{}")) : List.of()).build())
                .tokenUsage(TokenUsageEntity.of(3, 2, 1)).build();
    }

    private RuntimeProcessorTemplate runtime(ModelInvoker invoker, List<ToolExecuteResult> results,
                                             LoopInterceptor interceptor, int maxSteps, RuntimeListener listener) {
        return runtime(invoker, results, interceptor, maxSteps, listener, null);
    }

    private RuntimeProcessorTemplate runtime(ModelInvoker invoker, List<ToolExecuteResult> results,
                                             LoopInterceptor interceptor, int maxSteps, RuntimeListener listener,
                                             RuntimeLifeStyleManager lifecycle) {
        LoopInterceptor framework = new DefaultLoopInterceptor();
        return runtime(invoker, results,
                new DefaultLoopInterceptorProcessor(List.of(framework, interceptor)), maxSteps, listener, lifecycle);
    }

    private BoundaryChecker boundaryChecker(int maxSteps) {
        return new BoundaryChecker(AgentConfig.builder().maxIterations(maxSteps).build(),
                tokenizer, conversations, null, null);
    }

    private RuntimeProcessorTemplate runtime(ModelInvoker invoker, List<ToolExecuteResult> results,
                                             LoopInterceptorProcessor interceptorProcessor, int maxSteps,
                                             RuntimeListener listener, RuntimeLifeStyleManager lifecycle) {
        return runtime(invoker, results, interceptorProcessor, listener, lifecycle, boundaryChecker(maxSteps));
    }

    private RuntimeProcessorTemplate runtime(ModelInvoker invoker, List<ToolExecuteResult> results,
                                             LoopInterceptorProcessor interceptorProcessor,
                                             RuntimeListener listener, RuntimeLifeStyleManager lifecycle,
                                             RuntimeBoundaryChecker checker) {
        RuntimeEventPublisher events = new RuntimeEventPublisher(List.of(listener));
        ToolExecutionManager tools = new ToolExecutionManager() {
            public List<ToolExecuteResult> execute(ToolExecuteCommand command) { return results; }
            public ToolRegistry toolRegistry() { return new ToolRegistry(List.of()); }
        };
        RuntimeLifeStyleManager notifications = lifecycle == null ? new DefaultRuntimeLifeStyleManager(events) : lifecycle;
        return new RuntimeProcessorTemplate(RuntimeContext.builder().conversationManager(conversations)
                .executionRepository(repository)
                .executionControl(new DefaultExecutionController(() -> null, repository, events, notifications))
                .loopInterceptorProcessor(interceptorProcessor)
                .runtimeLifeStyleManager(notifications)
                .runtimeBoundaryChecker(checker)
                .usage(new ContextUsageReporter(tokenizer, 1_024_000, events, 1))
                .runtimeEventPublisher(events).toolExecutionManager(tools).invoker(invoker).build());
    }

    @Test
    void replacingLifecycleNotificationsCannotSkipRequiredStateTransitions() {
        RuntimeLifeStyleManager silentObserver = new RuntimeLifeStyleManager() {
            public void onStart(Execution execution) { }
            public void onCancel(Execution execution) { }
            public void onSuspend(Execution execution) { }
            public void onComplete(Execution execution) { }
            public void onError(Execution execution, Exception error) { }
            public void onResume(Execution execution) { }
        };
        Execution execution = execution();

        runtime(command -> response(false), List.of(), LoopInterceptor.NOOP, 1,
                new RuntimeListener() { }, silentObserver).execute(execution);

        assertEquals(ExecutionState.COMPLETED, execution.getExecutionState());
        assertNotNull(execution.getStartAt());
        assertNotNull(execution.getCompletedAt());
    }

    @Test
    void resumesReturnedSnapshotAndKeepsBudgetAndTerminalCallback() {
        Execution execution = execution();
        ToolExecuteResult written = ToolExecuteResult.success("written");
        AtomicReference<Execution> outcome = new AtomicReference<>();
        LoopInterceptor stopAfterTools = new LoopInterceptor() {
            @Override
            public int order() {
                return 0;
            }

            public InterceptorResult onAfterToolCall(LoopContext context, List<ToolExecuteResult> results) {
                repository.requireSuspend(context.execution().getId());
                return InterceptorResult.NONE;
            }
            public InterceptorResult onRunEnd(Execution finished) {
                outcome.set(finished);
                return InterceptorResult.NONE;
            }
        };
        runtime(command -> response(true), List.of(written), stopAfterTools, 2, new RuntimeListener() {})
                .execute(execution);
        assertEquals(ExecutionState.SUSPENDED, execution.getExecutionState());
        assertNull(outcome.get());
        // The returned execution stays mutable on purpose: the lifecycle callbacks observe this very
        // list, and a caller may still append to it before resuming. A frozen list here used to make
        // ConversationManager#appendUserMessage throw on any execution that had already run once.
        List<Message> liveMessages = execution.getMessages();
        int sizeBefore = liveMessages.size();
        liveMessages.add(UserMessageEntity.from("probe"));
        assertEquals(sizeBefore + 1, liveMessages.size());
        liveMessages.remove(sizeBefore);
        assertEquals(1, execution.getModelAttempts());
        runtime(command -> response(false), List.of(), stopAfterTools, 2, new RuntimeListener() {})
                .execute(execution);
        assertEquals(ExecutionState.COMPLETED, execution.getExecutionState());
        assertEquals(2, execution.getModelAttempts());
        assertSame(execution, outcome.get());
        assertEquals(2, transcripts.get());
    }

    /**
     * A callback that throws must not turn a successful conversation into a failed execution: the
     * processor logs it, and the interceptors registered after the failing one still run.
     */
    @Test
    void interceptorFailureIsContainedAndTheRunStillCompletes() {
        Execution execution = execution();
        AtomicInteger survived = new AtomicInteger();
        LoopInterceptor boom = new LoopInterceptor() {
            @Override
            public int order() {
                return 0;
            }

            public InterceptorResult onBeforeModelInvoke(LoopContext context) {
                throw new IllegalStateException("boom");
            }

            public InterceptorResult onAfterModelInvoke(LoopContext context, ChatResponseEntity response) {
                throw new IllegalStateException("boom");
            }
        };
        LoopInterceptor survivor = new LoopInterceptor() {
            @Override
            public int order() {
                return 10;
            }

            public InterceptorResult onBeforeModelInvoke(LoopContext context) {
                survived.incrementAndGet();
                return InterceptorResult.NONE;
            }
        };

        runtime(command -> response(false), List.of(),
                new DefaultLoopInterceptorProcessor(List.of(boom, survivor)), 1, new RuntimeListener() { }, null)
                .execute(execution);

        assertEquals(ExecutionState.COMPLETED, execution.getExecutionState());
        assertTrue(execution.getMessages().stream().anyMatch(AiMessageEntity.class::isInstance));
        assertEquals(1, survived.get());
    }

    @Test
    void oneStepBudgetAllowsExactlyOneModelCallAndFinalUsageFlush() {
        AtomicInteger calls = new AtomicInteger();
        AtomicInteger updates = new AtomicInteger();
        Execution execution = execution();
        runtime(command -> { calls.incrementAndGet(); return response(false); }, List.of(), LoopInterceptor.NOOP,
                1, new RuntimeListener() {
                    public void onContextUpdate(ContextUpdateEvent event) { updates.incrementAndGet(); }
                }).execute(execution);
        assertEquals(ExecutionState.COMPLETED, execution.getExecutionState());
        assertEquals(1, calls.get());
        assertEquals(1, updates.get());
    }

    @Test
    void resumeDoesNotResetConsumedBudget() {
        Execution execution = execution();
        execution.setExecutionState(ExecutionState.SUSPENDED);
        execution.setModelAttempts(1);
        AtomicInteger calls = new AtomicInteger();
        assertThrows(MaxStepsExceededException.class,
                () -> runtime(command -> { calls.incrementAndGet(); return response(false); }, List.of(),
                        LoopInterceptor.NOOP, 1, new RuntimeListener() {}).execute(execution));
        assertEquals(0, calls.get());
        assertEquals(ExecutionState.FAILED, execution.getExecutionState());
    }

    @Test
    void cancelDuringPlainTextResponseWinsBeforeCommit() {
        Execution execution = execution();
        runtime(command -> { repository.requireCancel("e"); return response(false); }, List.of(),
                LoopInterceptor.NOOP, 1, new RuntimeListener() {}).execute(execution);
        assertEquals(ExecutionState.CANCELLED, execution.getExecutionState());
        assertEquals(0, transcripts.get());
    }

    @Test
    void blankCompactionIsCommittedAsARegularRound() {
        AtomicInteger calls = new AtomicInteger();
        Execution execution = execution();
        runtime(command -> response(calls.getAndIncrement() == 0),
                List.of(ToolExecuteResult.success(" ", ToolResultType.CONTEXT_COMPACT)),
                LoopInterceptor.NOOP, 2, new RuntimeListener() {}).execute(execution);
        assertEquals(ExecutionState.COMPLETED, execution.getExecutionState());
        assertEquals(2, transcripts.get());
        assertTrue(execution.getMessages().stream().anyMatch(ToolMessageEntity.class::isInstance));
    }

    /**
     * Compaction is verified on the context the model was actually served, not on the execution
     * snapshot: the live context must carry the rebuilt summary while the compact round itself is
     * kept out of it, and still recorded in the transcript.
     */
    @Test
    void successfulCompactionRecordsTranscriptWithoutKeepingCompactRoundInContext() {
        AtomicInteger calls = new AtomicInteger();
        AtomicReference<List<Message>> served = new AtomicReference<>();
        Execution execution = execution();
        ModelInvoker invoker = command -> {
            served.set(command.chatRequest().getMessages());
            return response(calls.getAndIncrement() == 0);
        };
        runtime(invoker,
                List.of(ToolExecuteResult.success("{\"summary\":\"finished earlier work\"}", ToolResultType.CONTEXT_COMPACT)),
                LoopInterceptor.NOOP, 2, new RuntimeListener() {}).execute(execution);
        List<Message> compactedContext = served.get();
        assertEquals(2, transcripts.get());
        assertEquals(6, execution.getTokenUsage().getTotalTokens());
        assertTrue(systemTexts(compactedContext).stream().anyMatch(text -> text.contains("finished earlier work")));
        assertFalse(compactedContext.stream().anyMatch(ToolMessageEntity.class::isInstance));
        assertFalse(execution.getMessages().stream().anyMatch(ToolMessageEntity.class::isInstance));
    }

    private List<String> systemTexts(List<Message> messages) {
        return messages.stream()
                .filter(SystemMessageEntity.class::isInstance)
                .map(SystemMessageEntity.class::cast)
                .map(SystemMessageEntity::getText)
                .toList();
    }

    @Test
    void mixedCompactionBatchKeepsEveryToolResult() {
        AtomicInteger calls = new AtomicInteger();
        Execution execution = execution();
        runtime(command -> {
            if (calls.getAndIncrement() > 0) return response(false);
            ChatResponseEntity response = response(true);
            response.getAiMessageEntity().setToolCalls(List.of(
                    new ToolCallRequest("a", "compact", "{}"), new ToolCallRequest("b", "read", "{}")));
            return response;
        }, List.of(ToolExecuteResult.success("summary", ToolResultType.CONTEXT_COMPACT), ToolExecuteResult.success("read result")),
                LoopInterceptor.NOOP, 2, new RuntimeListener() {}).execute(execution);
        assertEquals(2, transcripts.get());
        assertEquals(2, execution.getMessages().stream().filter(ToolMessageEntity.class::isInstance).count());
    }


    @Test
    void emptyInterceptorRegistryStillEnforcesStepBudget() {
        Execution execution = execution();
        AtomicInteger calls = new AtomicInteger();

        assertThrows(MaxStepsExceededException.class, () -> runtime(command -> {
            calls.incrementAndGet();
            return response(true);
        }, List.of(ToolExecuteResult.success("written")), new DefaultLoopInterceptorProcessor(List.of()),
                1, new RuntimeListener() { }, null).execute(execution));

        assertEquals(1, calls.get());
        assertEquals(1, execution.getModelAttempts());
        assertEquals(ExecutionState.FAILED, execution.getExecutionState());
        assertEquals(1, transcripts.get());
    }

    @Test
    void boundarySeesInputFromEveryBeforeModelCallbackBeforeTheRequestIsBuilt() {
        Execution execution = execution();
        List<String> phases = new ArrayList<>();
        LoopInterceptor first = inputAppender("first", -800, phases);
        LoopInterceptor last = inputAppender("last", Integer.MAX_VALUE, phases);
        RuntimeBoundaryChecker checker = new RuntimeBoundaryChecker() {
            public CheckPointResult before(Execution current) {
                phases.add("boundary");
                assertEquals(0, current.getModelAttempts());
                assertTrue(current.getMessages().stream().anyMatch(message -> message.text().equals("first")));
                assertTrue(current.getMessages().stream().anyMatch(message -> message.text().equals("last")));
                conversations.appendSystemMessage(current, "checked input");
                return CheckPointResult.continueWith();
            }

            public CheckPointResult after(Execution current) {
                fail("A final plain-text response does not take the ordinary tool boundary");
                return CheckPointResult.continueWith();
            }
        };

        runtime(command -> {
            phases.add("model");
            assertEquals(1, execution.getModelAttempts());
            assertTrue(command.chatRequest().getMessages().stream()
                    .anyMatch(message -> message.text().equals("checked input")));
            return response(false);
        }, List.of(), new DefaultLoopInterceptorProcessor(List.of(last, new DefaultLoopInterceptor(), first)),
                new RuntimeListener() { }, null, checker).execute(execution);

        assertEquals(List.of("first", "last", "boundary", "model"), phases);
        assertEquals(ExecutionState.COMPLETED, execution.getExecutionState());
    }

    @Test
    void boundaryCanRejectAppendedInputWithoutSpendingAModelAttempt() {
        Execution execution = execution();
        AtomicInteger calls = new AtomicInteger();
        RuntimeBoundaryChecker checker = new RuntimeBoundaryChecker() {
            public CheckPointResult before(Execution current) {
                assertTrue(current.getMessages().stream().anyMatch(message -> message.text().equals("large input")));
                return CheckPointResult.cancelWith("input exceeds context budget");
            }

            public CheckPointResult after(Execution current) {
                fail("No tool round should be entered");
                return CheckPointResult.continueWith();
            }
        };

        runtime(command -> {
            calls.incrementAndGet();
            return response(false);
        }, List.of(), new DefaultLoopInterceptorProcessor(List.of(inputAppender("large input", 0, new ArrayList<>()))),
                new RuntimeListener() { }, null, checker).execute(execution);

        assertEquals(ExecutionState.CANCELLED, execution.getExecutionState());
        assertEquals(0, calls.get());
        assertEquals(0, execution.getModelAttempts());
    }

    @Test
    void beforeModelShortCircuitDoesNotConsumeBudget() {
        Execution execution = execution();
        AtomicInteger calls = new AtomicInteger();
        LoopInterceptor stop = new LoopInterceptor() {
            public int order() { return 0; }

            public InterceptorResult onBeforeModelInvoke(LoopContext context) {
                return InterceptorResult.of(LoopResult.suspended("waiting for input"));
            }
        };

        runtime(command -> {
            calls.incrementAndGet();
            return response(false);
        }, List.of(), stop, 1, new RuntimeListener() { }).execute(execution);

        assertEquals(ExecutionState.SUSPENDED, execution.getExecutionState());
        assertEquals(0, calls.get());
        assertEquals(0, execution.getModelAttempts());
    }

    @Test
    void cancellationRequestedByACallbackSkipsTheBoundaryAndModel() {
        Execution execution = execution();
        AtomicInteger calls = new AtomicInteger();
        LoopInterceptor stop = new LoopInterceptor() {
            public int order() { return 0; }

            public InterceptorResult onBeforeModelInvoke(LoopContext context) {
                context.signal().requireCancel();
                return InterceptorResult.NONE;
            }
        };
        RuntimeBoundaryChecker checker = new RuntimeBoundaryChecker() {
            public CheckPointResult before(Execution current) {
                fail("Stopped executions must not enter blocking compaction");
                return CheckPointResult.continueWith();
            }

            public CheckPointResult after(Execution current) {
                fail("No tool round should be entered");
                return CheckPointResult.continueWith();
            }
        };

        runtime(command -> {
            calls.incrementAndGet();
            return response(false);
        }, List.of(), new DefaultLoopInterceptorProcessor(List.of(stop)),
                new RuntimeListener() { }, null, checker).execute(execution);

        assertEquals(ExecutionState.CANCELLED, execution.getExecutionState());
        assertEquals(0, calls.get());
        assertEquals(0, execution.getModelAttempts());
    }

    @Test
    void suspensionDuringTheBoundaryWinsWithoutSpendingAnAttempt() {
        Execution execution = execution();
        AtomicInteger calls = new AtomicInteger();
        RuntimeBoundaryChecker checker = new RuntimeBoundaryChecker() {
            public CheckPointResult before(Execution current) {
                repository.requireSuspend(current.getId());
                return CheckPointResult.cancelWith("boundary cancellation");
            }

            public CheckPointResult after(Execution current) {
                fail("No tool round should be entered");
                return CheckPointResult.continueWith();
            }
        };

        runtime(command -> {
            calls.incrementAndGet();
            return response(false);
        }, List.of(), new DefaultLoopInterceptorProcessor(List.of()),
                new RuntimeListener() { }, null, checker).execute(execution);

        assertEquals(ExecutionState.SUSPENDED, execution.getExecutionState());
        assertEquals(0, calls.get());
        assertEquals(0, execution.getModelAttempts());
    }

    @Test
    void postToolBoundaryChangesAreSavedBeforeTheNextModelAttempt() {
        Execution execution = execution();
        AtomicInteger calls = new AtomicInteger();
        List<String> phases = new ArrayList<>();
        RuntimeBoundaryChecker checker = new RuntimeBoundaryChecker() {
            public CheckPointResult before(Execution current) {
                return CheckPointResult.continueWith();
            }

            public CheckPointResult after(Execution current) {
                phases.add("boundary");
                assertEquals(1, current.getModelAttempts());
                assertTrue(current.getMessages().stream().anyMatch(ToolMessageEntity.class::isInstance));
                conversations.appendSystemMessage(current, "checked tool round");
                return CheckPointResult.continueWith();
            }
        };
        LoopInterceptor observer = new LoopInterceptor() {
            public int order() { return 0; }

            public InterceptorResult onAfterToolCall(LoopContext context, List<ToolExecuteResult> results) {
                phases.add("callback");
                return InterceptorResult.NONE;
            }
        };

        runtime(command -> {
            if (calls.getAndIncrement() == 0) return response(true);
            phases.add("next model");
            Execution saved = repository.findById(execution.getId()).orElseThrow();
            assertTrue(saved.getMessages().stream().anyMatch(message -> message.text().equals("checked tool round")));
            return response(false);
        }, List.of(ToolExecuteResult.success("written")),
                new DefaultLoopInterceptorProcessor(List.of(observer)), new RuntimeListener() { }, null, checker)
                .execute(execution);

        assertEquals(List.of("callback", "boundary", "next model"), phases);
        assertEquals(ExecutionState.COMPLETED, execution.getExecutionState());
        assertEquals(2, execution.getModelAttempts());
    }

    @Test
    void promiseRoundKeepsItsCheckpointWithoutOrdinaryPostToolCompaction() {
        Execution execution = execution();
        RuntimeBoundaryChecker checker = new RuntimeBoundaryChecker() {
            public CheckPointResult before(Execution current) {
                return CheckPointResult.continueWith();
            }

            public CheckPointResult after(Execution current) {
                fail("A promise placeholder must remain available to the resuming application");
                return CheckPointResult.continueWith();
            }
        };

        runtime(command -> response(true), List.of(ToolExecuteResult.promise("waiting")),
                new DefaultLoopInterceptorProcessor(List.of()), new RuntimeListener() { }, null, checker)
                .execute(execution);

        Execution saved = repository.findById(execution.getId()).orElseThrow();
        assertEquals(ExecutionState.SUSPENDED, saved.getExecutionState());
        assertEquals(1, saved.getModelAttempts());
        assertTrue(saved.getMessages().stream().anyMatch(ToolMessageEntity.class::isInstance));
        assertEquals(1, transcripts.get());
    }

    @Test
    void fatalBeforeModelCallbackDoesNotConsumeAnAttempt() {
        Execution execution = execution();
        AtomicInteger calls = new AtomicInteger();
        IllegalStateException failure = new IllegalStateException("input could not be accepted");
        LoopInterceptor interceptor = new LoopInterceptor() {
            public int order() { return 0; }
            public boolean catchErr() { return false; }

            public InterceptorResult onBeforeModelInvoke(LoopContext context) {
                throw failure;
            }
        };

        assertSame(failure, assertThrows(IllegalStateException.class, () -> runtime(command -> {
            calls.incrementAndGet();
            return response(false);
        }, List.of(), interceptor, 1, new RuntimeListener() { }).execute(execution)));

        assertEquals(ExecutionState.FAILED, execution.getExecutionState());
        assertEquals(0, calls.get());
        assertEquals(0, execution.getModelAttempts());
    }

    @Test
    void postToolBoundaryFailurePropagatesOutsideTheInterceptorChain() {
        Execution execution = execution();
        AtomicInteger calls = new AtomicInteger();
        IllegalStateException failure = new IllegalStateException("tool round exceeds context budget");
        RuntimeBoundaryChecker checker = new RuntimeBoundaryChecker() {
            public CheckPointResult before(Execution current) {
                return CheckPointResult.continueWith();
            }

            public CheckPointResult after(Execution current) {
                throw failure;
            }
        };

        assertSame(failure, assertThrows(IllegalStateException.class, () -> runtime(command -> {
            calls.incrementAndGet();
            return response(true);
        }, List.of(ToolExecuteResult.success("written")), new DefaultLoopInterceptorProcessor(List.of(LoopInterceptor.NOOP)),
                new RuntimeListener() { }, null, checker).execute(execution)));

        assertEquals(ExecutionState.FAILED, execution.getExecutionState());
        assertEquals(failure.getMessage(), execution.getErrorMessage());
        assertEquals(1, execution.getModelAttempts());
        assertEquals(1, calls.get());
    }

    private LoopInterceptor inputAppender(String text, int order, List<String> phases) {
        return new LoopInterceptor() {
            public int order() { return order; }

            public InterceptorResult onBeforeModelInvoke(LoopContext context) {
                phases.add(text);
                context.appendMessage(List.of(UserMessageEntity.from(text)));
                return InterceptorResult.NONE;
            }
        };
    }

    @Test
    void fatalRoundEndFailureTurnsAnOtherwiseSuccessfulRoundIntoFailure() {
        Execution execution = execution();
        IllegalStateException failure = new IllegalStateException("round projection failed");

        assertSame(failure, assertThrows(IllegalStateException.class, () ->
                runtime(command -> response(false), List.of(), failingRoundEnd(failure), 1,
                        new RuntimeListener() { }).execute(execution)));

        assertEquals(ExecutionState.FAILED, execution.getExecutionState());
        assertEquals(failure.getMessage(), execution.getErrorMessage());
        assertEquals(1, transcripts.get());
        assertThrows(IllegalStateException.class, () -> repository.requireCancel(execution.getId()));
    }

    @Test
    void modelFailureKeepsItsIdentityWhenRoundEndAlsoFails() {
        Execution execution = execution();
        IllegalStateException primary = new IllegalStateException("model failed");
        IllegalStateException cleanup = new IllegalStateException("round projection failed");

        assertSame(primary, assertThrows(IllegalStateException.class, () ->
                runtime(command -> { throw primary; }, List.of(), failingRoundEnd(cleanup), 1,
                        new RuntimeListener() { }).execute(execution)));

        assertArrayEquals(new Throwable[] { cleanup }, primary.getSuppressed());
        assertEquals(ExecutionState.FAILED, execution.getExecutionState());
        assertEquals(primary.getMessage(), execution.getErrorMessage());
        assertThrows(IllegalStateException.class, () -> repository.requireCancel(execution.getId()));
    }

    @Test
    void checkedModelFailureRetainsTheSuppressedRoundEndFailure() {
        Execution execution = execution();
        Exception primary = new Exception("checked model failure");
        IllegalStateException cleanup = new IllegalStateException("round projection failed");

        RuntimeException thrown = assertThrows(RuntimeException.class, () ->
                runtime(command -> { throw primary; }, List.of(), failingRoundEnd(cleanup), 1,
                        new RuntimeListener() { }).execute(execution));

        assertSame(primary, thrown.getCause());
        assertArrayEquals(new Throwable[] { cleanup }, primary.getSuppressed());
        assertEquals(primary.getMessage(), execution.getErrorMessage());
    }

    @Test
    void containedRoundEndFailureStillNotifiesTheNextInterceptor() {
        Execution execution = execution();
        AtomicInteger notified = new AtomicInteger();
        LoopInterceptor failing = new LoopInterceptor() {
            public int order() { return 0; }

            public InterceptorResult onLoopEnd(LoopContext context) {
                throw new IllegalStateException("optional observer failed");
            }
        };
        LoopInterceptor following = new LoopInterceptor() {
            public int order() { return 10; }

            public InterceptorResult onLoopEnd(LoopContext context) {
                notified.incrementAndGet();
                return InterceptorResult.NONE;
            }
        };

        runtime(command -> response(false), List.of(),
                new DefaultLoopInterceptorProcessor(List.of(failing, following)), 1,
                new RuntimeListener() { }, null).execute(execution);

        assertEquals(ExecutionState.COMPLETED, execution.getExecutionState());
        assertEquals(1, notified.get());
    }

    @Test
    void fatalRunEndFailureCannotChangeTheCommittedSuccessOrReturnValue() {
        Execution execution = execution();
        AtomicInteger notified = new AtomicInteger();
        LoopInterceptor interceptor = new LoopInterceptor() {
            public int order() { return 0; }
            public boolean catchErr() { return false; }

            public InterceptorResult onRunEnd(Execution finished) {
                notified.incrementAndGet();
                assertEquals(ExecutionState.COMPLETED, finished.getExecutionState());
                assertThrows(IllegalStateException.class, () -> repository.requireCancel(finished.getId()));
                throw new IllegalStateException("terminal observer failed");
            }
        };

        Execution returned = runtime(command -> response(false), List.of(), interceptor, 1,
                new RuntimeListener() { }).execute(execution);

        assertSame(execution, returned);
        assertEquals(ExecutionState.COMPLETED, execution.getExecutionState());
        assertEquals(1, notified.get());
    }

    @Test
    void fatalRunEndFailureCannotReplaceTheOriginalExecutionFailure() {
        Execution execution = execution();
        IllegalStateException primary = new IllegalStateException("model failed");
        AtomicInteger notified = new AtomicInteger();
        LoopInterceptor interceptor = new LoopInterceptor() {
            public int order() { return 0; }
            public boolean catchErr() { return false; }

            public InterceptorResult onRunEnd(Execution finished) {
                notified.incrementAndGet();
                assertEquals(ExecutionState.FAILED, finished.getExecutionState());
                assertThrows(IllegalStateException.class, () -> repository.requireCancel(finished.getId()));
                throw new IllegalStateException("terminal observer failed");
            }
        };

        assertSame(primary, assertThrows(IllegalStateException.class, () ->
                runtime(command -> { throw primary; }, List.of(), interceptor, 1,
                        new RuntimeListener() { }).execute(execution)));

        assertEquals(primary.getMessage(), execution.getErrorMessage());
        assertEquals(1, notified.get());
    }

    private LoopInterceptor failingRoundEnd(IllegalStateException failure) {
        return new LoopInterceptor() {
            public int order() { return 0; }
            public boolean catchErr() { return false; }

            public InterceptorResult onLoopEnd(LoopContext context) {
                throw failure;
            }
        };
    }

    @Test
    void snapshotsDoNotChangeWithLiveExecutionOrRestoredCopies() {
        Execution execution = execution();
        repository.save(execution);
        execution.getMessages().add(UserMessageEntity.from("uncommitted"));
        Execution restored = repository.findById("e").orElseThrow();
        assertEquals(1, restored.getMessages().size());
        restored.getMessages().clear();
        assertEquals(1, repository.findById("e").orElseThrow().getMessages().size());
    }
}
