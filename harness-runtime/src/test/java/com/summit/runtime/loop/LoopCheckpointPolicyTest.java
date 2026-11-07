package com.summit.runtime.loop;

import com.summit.core.agent.AgentRequest;
import com.summit.core.agent.Execution;
import com.summit.core.agent.ExecutionState;
import com.summit.core.compact.ContextAttachmentProvider;
import com.summit.core.conversation.api.ChatResponseEntity;
import com.summit.core.conversation.api.ToolCallRequest;
import com.summit.core.conversation.event.AgentAuditRecordedEvent;
import com.summit.core.conversation.event.AgentTokenAuditRecordedEvent;
import com.summit.core.conversation.event.RuntimeEventPublisher;
import com.summit.core.conversation.event.TokenInfo;
import com.summit.core.conversation.message.AiMessageEntity;
import com.summit.core.conversation.message.Message;
import com.summit.core.conversation.message.TokenUsageEntity;
import com.summit.core.conversation.message.UserMessageEntity;
import com.summit.core.model.chat.ChatModel;
import com.summit.core.runtime.RuntimeListener;
import com.summit.core.runtime.loop.ContextUsageReporter;
import com.summit.core.runtime.loop.ExecutionControlSignal;
import com.summit.core.runtime.loop.ExecutionRepository;
import com.summit.core.runtime.loop.InterceptorResult;
import com.summit.core.runtime.loop.LoopContext;
import com.summit.core.runtime.loop.LoopInterceptor;
import com.summit.core.tool.ToolExecuteCommand;
import com.summit.core.tool.ToolExecuteResult;
import com.summit.core.tool.ToolExecutionManager;
import com.summit.core.tool.ToolRegistry;
import com.summit.runtime.agent.AgentConfig;
import com.summit.runtime.audit.DefaultTokenAuditor;
import com.summit.runtime.audit.ExecutionTokenAuditRecorder;
import com.summit.runtime.context.RuntimeContext;
import com.summit.runtime.conversation.DefaultConversationManager;
import com.summit.runtime.conversation.DefaultTokenizer;
import com.summit.runtime.loop.control.InMemoryActiveExecutionRegistry;
import com.summit.runtime.model.DefaultChatModelInvoker;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

class LoopCheckpointPolicyTest {
    private final RecordingRepository repository = new RecordingRepository();
    private final List<AgentTokenAuditRecordedEvent> audits = new ArrayList<>();

    @Test
    void ordinaryRoundsDoNotPersistIntermediateSnapshots() {
        AtomicInteger calls = new AtomicInteger();
        Execution result = runtime(request -> response(calls.incrementAndGet() < 3), LoopInterceptor.NOOP)
                .execute(execution());

        assertEquals(3, calls.get());
        assertEquals(ExecutionState.COMPLETED, result.getExecutionState());
        assertFalse(repository.attempts.stream().anyMatch(attempt -> attempt == 1 || attempt == 2),
                "Ordinary committed rounds must not force snapshot writes");
        assertEquals(3, repository.attempts.getLast());
        assertEquals(ExecutionState.COMPLETED, repository.states.getLast());
        assertNotNull(result.getContextUsageMetric());
        assertEquals(1, audits.size());
        var audit = audits.getFirst();
        assertEquals(result.getId(), audit.executionId());
        assertEquals(ExecutionState.COMPLETED, audit.executionState());
        assertEquals(TokenInfo.from(result.getTokenUsage()), audit.tokenInfo());
        assertNotNull(audit.contextAudit());
    }

    @Test
    void applicationCanPersistEachCommittedRoundFromItsEndCallback() {
        AtomicInteger calls = new AtomicInteger();
        AtomicInteger checkpoints = new AtomicInteger();
        LoopInterceptor application = new LoopInterceptor() {
            public int order() { return -900; }
            public boolean catchErr() { return false; }
            public InterceptorResult onLoopEnd(LoopContext context) {
                Execution current = context.getLoopMessages().getExecution();
                repository.save(current);
                Execution restored = repository.findById(current.getId()).orElseThrow();
                assertEquals(checkpoints.incrementAndGet(), restored.getModelAttempts());
                assertEquals(current.getMessages().size(), restored.getMessages().size());
                assertEquals(ExecutionState.RUNNING, restored.getExecutionState());
                return InterceptorResult.NONE;
            }
        };

        runtime(request -> response(calls.incrementAndGet() < 3), application).execute(execution());

        assertEquals(3, checkpoints.get());
        assertTrue(repository.attempts.containsAll(List.of(1, 2, 3)));
    }

    @ParameterizedTest
    @ValueSource(strings = {"suspend", "cancel"})
    void requestedControlStillAllowsApplicationRoundEndPersistence(String control) {
        AtomicInteger checkpoints = new AtomicInteger();
        LoopInterceptor application = new LoopInterceptor() {
            public int order() { return -900; }
            public boolean catchErr() { return false; }
            public InterceptorResult onLoopStart(LoopContext context) {
                if (control.equals("cancel")) context.getSignal().requireCancel();
                else context.getSignal().requireSuspend();
                return InterceptorResult.NONE;
            }
            public InterceptorResult onLoopEnd(LoopContext context) {
                repository.save(context.getLoopMessages().getExecution());
                checkpoints.incrementAndGet();
                return InterceptorResult.NONE;
            }
        };

        Execution result = runtime(request -> {
            fail("A control request must stop before model invocation");
            return response(false);
        }, application).execute(execution());

        assertEquals(1, checkpoints.get());
        ExecutionState expected = control.equals("cancel") ? ExecutionState.CANCELLED : ExecutionState.SUSPENDED;
        assertEquals(expected, result.getExecutionState());
        assertEquals(expected, repository.states.getLast());
        if (expected == ExecutionState.SUSPENDED) {
            assertEquals(expected, repository.findById(result.getId()).orElseThrow().getExecutionState());
            assertTrue(audits.isEmpty());
        } else {
            assertEquals(1, audits.size());
            assertEquals(expected, audits.getFirst().executionState());
        }
    }

    @Test
    void mandatoryRoundEndFailureStopsBeforeAnotherModelCall() {
        AtomicInteger calls = new AtomicInteger();
        IllegalStateException failure = new IllegalStateException("snapshot write failed");
        Execution current = execution();

        assertSame(failure, assertThrows(IllegalStateException.class, () ->
                runtime(request -> response(calls.incrementAndGet() == 1), failingCheckpoint(failure))
                        .execute(current)));

        assertEquals(1, calls.get());
        assertEquals(ExecutionState.FAILED, current.getExecutionState());
        assertEquals(ExecutionState.FAILED, repository.states.getLast());
        assertEquals(1, audits.size());
        assertEquals(ExecutionState.FAILED, audits.getFirst().executionState());
    }

    @Test
    void checkpointFailureDoesNotReplaceThePrimaryRoundFailure() {
        IllegalStateException primary = new IllegalStateException("model failed");
        IllegalStateException checkpoint = new IllegalStateException("snapshot write failed");

        assertSame(primary, assertThrows(IllegalStateException.class, () ->
                runtime(request -> { throw primary; }, failingCheckpoint(checkpoint)).execute(execution())));

        assertTrue(Arrays.asList(primary.getSuppressed()).contains(checkpoint));
        assertEquals(ExecutionState.FAILED, repository.states.getLast());
    }

    private LoopInterceptor failingCheckpoint(IllegalStateException failure) {
        return new LoopInterceptor() {
            public int order() { return -900; }
            public boolean catchErr() { return false; }
            public InterceptorResult onLoopEnd(LoopContext context) { throw failure; }
        };
    }

    private RuntimeProcessorTemplate runtime(ChatModel model, LoopInterceptor application) {
        RuntimeEventPublisher events = new RuntimeEventPublisher(List.of(new RuntimeListener() {
            @Override
            public void onAuditRecorded(AgentAuditRecordedEvent event) {
                audits.add(assertInstanceOf(AgentTokenAuditRecordedEvent.class, event));
            }
        }));
        DefaultTokenizer tokenizer = new DefaultTokenizer();
        AgentConfig config = AgentConfig.builder().maxIterations(5).build();
        DefaultRuntimeLifeStyleManager lifecycle = new DefaultRuntimeLifeStyleManager(events,
                new ExecutionTokenAuditRecorder(new DefaultTokenAuditor(tokenizer, config), events));
        DefaultConversationManager conversations = new DefaultConversationManager(ContextAttachmentProvider.NONE);
        ToolExecutionManager tools = new ToolExecutionManager() {
            public List<ToolExecuteResult> execute(ToolExecuteCommand command) {
                return List.of(ToolExecuteResult.success("tool result"));
            }
            public ToolRegistry toolRegistry() { return new ToolRegistry(List.of()); }
        };
        return new RuntimeProcessorTemplate(RuntimeContext.builder().conversationManager(conversations)
                .executionRepository(repository)
                .executionControl(new DefaultExecutionController(() -> null, repository, events, lifecycle))
                .runtimeLifeStyleManager(lifecycle).runtimeEventPublisher(events)
                .loopInterceptorProcessor(new DefaultLoopInterceptorProcessor(
                        List.of(new DefaultLoopInterceptor(), application)))
                .runtimeBoundaryChecker(new BoundaryChecker(config, tokenizer, conversations, null, null, null, null))
                .toolExecutionManager(tools).invoker(new DefaultChatModelInvoker(model))
                .usage(new ContextUsageReporter(tokenizer, 100_000, events, 1)).build());
    }

    private Execution execution() {
        List<Message> messages = List.of(UserMessageEntity.from("task"));
        return Execution.builder().id("policy").executionState(ExecutionState.CREATED)
                .agentRequest(AgentRequest.builder().messages(messages).build())
                .messages(new ArrayList<>(messages)).tokenUsage(TokenUsageEntity.empty()).build();
    }

    private ChatResponseEntity response(boolean tools) {
        return ChatResponseEntity.builder().aiMessageEntity(AiMessageEntity.builder().text("answer")
                .toolCalls(tools ? List.of(new ToolCallRequest("call", "test", 0, "{}")) : List.of()).build())
                .tokenUsage(TokenUsageEntity.of(3, 2, 1)).build();
    }

    private static final class RecordingRepository implements ExecutionRepository {
        private final InMemoryActiveExecutionRegistry delegate = new InMemoryActiveExecutionRegistry();
        private final List<Integer> attempts = new ArrayList<>();
        private final List<ExecutionState> states = new ArrayList<>();

        public void save(Execution execution) {
            attempts.add(execution.getModelAttempts());
            states.add(execution.getExecutionState());
            delegate.save(execution);
        }
        public Optional<Execution> findById(String executionId) { return delegate.findById(executionId); }
        public ExecutionControlSignal register(String executionId) { return delegate.register(executionId); }
        public void unregister(ExecutionControlSignal signal) { delegate.unregister(signal); }
        public void requireSuspend(String executionId) { delegate.requireSuspend(executionId); }
        public void requireCancel(String executionId) { delegate.requireCancel(executionId); }
    }
}
