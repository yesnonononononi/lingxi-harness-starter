package com.summit.runtime.loop;

import com.summit.core.agent.*;
import com.summit.core.compact.ContextAttachmentProvider;
import com.summit.core.conversation.api.*;
import com.summit.core.conversation.context.RuntimeContext;
import com.summit.core.conversation.event.*;
import com.summit.core.conversation.message.*;
import com.summit.core.model.ModelInvoker;
import com.summit.core.runtime.RuntimeListener;
import com.summit.core.runtime.loop.*;
import com.summit.core.tool.*;
import com.summit.runtime.agent.AgentConfig;
import com.summit.runtime.conversation.*;
import com.summit.runtime.loop.control.InMemoryActiveExecutionRegistry;
import com.summit.runtime.loop.lifeStyle.DefaultRuntimeLifeStyleManager;
import org.junit.jupiter.api.Test;

import java.util.*;
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

    private Execution execution() {
        var messages = List.<Message>of(UserMessageEntity.from("task"));
        return Execution.builder().id("e").agentId("a").executionState(ExecutionState.CREATED)
                .agentRequest(AgentRequest.builder().messages(messages).build())
                .messages(new ArrayList<>(messages)).tokenUsage(TokenUsageEntity.empty()).build();
    }

    private ChatResponseEntity response(boolean tools) {
        return ChatResponseEntity.builder().aiMessageEntity(AiMessageEntity.builder().text("answer")
                .toolCalls(tools ? List.of(new ToolCallRequest("c", "test", "{}", null)) : List.of()).build())
                .tokenUsage(TokenUsageEntity.of(3, 2, 1)).build();
    }

    private RuntimeProcessorTemplate runtime(ModelInvoker invoker, List<ToolExecuteResult> results,
                                             LoopInterceptor interceptor, int maxSteps, RuntimeListener listener) {
        var events = new RuntimeEventPublisher(List.of(listener));
        var tools = new ToolExecutionManager() {
            public List<ToolExecuteResult> execute(ToolExecuteCommand command) { return results; }
            public ToolRegistry toolRegistry() { return new ToolRegistry(List.of()); }
        };
        return new RuntimeProcessorTemplate(RuntimeContext.builder().conversationManager(conversations)
                .executionRepository(repository).loopInterceptor(interceptor)
                .runtimeLifeStyleManager(new DefaultRuntimeLifeStyleManager(events))
                .runtimeBoundaryChecker(new BoundaryChecker(AgentConfig.builder().maxIterations(maxSteps).build(),
                        tokenizer, conversations, null, null))
                .usage(new ContextUsageReporter(tokenizer, 1_024_000, events, 1))
                .runtimeEventPublisher(events).toolExecutionManager(tools).invoker(invoker).build());
    }

    @Test
    void resumesReturnedImmutableSnapshotAndKeepsBudgetAndTerminalCallback() {
        Execution execution = execution();
        ToolExecuteResult written = ToolExecuteResult.success("written");
        AtomicReference<Execution> outcome = new AtomicReference<>();
        LoopInterceptor stopAfterTools = new LoopInterceptor() {
            public void onAfterToolCall(LoopContext context, List<ToolExecuteResult> results) {
                repository.requireSuspend(context.executionId());
            }
            public void onRunEnd(Execution finished) { outcome.set(finished); }
        };
        runtime(command -> response(true), List.of(written), stopAfterTools, 2, new RuntimeListener() {})
                .execute(execution);
        assertEquals(ExecutionState.SUSPENDED, execution.getExecutionState());
        assertNull(outcome.get());
        assertThrows(UnsupportedOperationException.class, () -> execution.getMessages().clear());
        assertEquals(1, execution.getModelAttempts());
        runtime(command -> response(false), List.of(), stopAfterTools, 2, new RuntimeListener() {})
                .execute(execution);
        assertEquals(ExecutionState.COMPLETED, execution.getExecutionState());
        assertEquals(2, execution.getModelAttempts());
        assertSame(execution, outcome.get());
        assertEquals(2, transcripts.get());
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
        assertThrows(com.summit.core.exception.MaxStepsExceededException.class,
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
            var response = response(true);
            response.getAiMessageEntity().setToolCalls(List.of(
                    new ToolCallRequest("a", "compact", "{}", null), new ToolCallRequest("b", "read", "{}", null)));
            return response;
        }, List.of(ToolExecuteResult.success("summary", ToolResultType.CONTEXT_COMPACT), ToolExecuteResult.success("read result")),
                LoopInterceptor.NOOP, 2, new RuntimeListener() {}).execute(execution);
        assertEquals(2, transcripts.get());
        assertEquals(2, execution.getMessages().stream().filter(ToolMessageEntity.class::isInstance).count());
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
