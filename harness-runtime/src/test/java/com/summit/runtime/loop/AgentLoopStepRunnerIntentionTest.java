package com.summit.runtime.loop;

import com.summit.core.agent.AgentRequest;
import com.summit.core.agent.Execution;
import com.summit.core.compact.ContextSummary;
import com.summit.core.conversation.ConversationManager;
import com.summit.core.conversation.api.ChatResponseEntity;
import com.summit.core.conversation.api.ToolCallRequest;
import com.summit.core.conversation.context.RuntimeContext;
import com.summit.core.conversation.event.RuntimeEventPublisher;
import com.summit.core.conversation.message.AiMessageEntity;
import com.summit.core.conversation.message.Message;
import com.summit.core.conversation.message.TokenUsageEntity;
import com.summit.core.runtime.loop.CheckPointResult;
import com.summit.core.runtime.loop.ActiveExecutionRegistry;
import com.summit.core.runtime.loop.ExecutionControlSignal;
import com.summit.core.runtime.loop.LoopResult;
import com.summit.core.runtime.loop.RuntimeBoundaryChecker;
import com.summit.core.runtime.loop.suspension.ExecutionInterruptedException;
import com.summit.core.runtime.loop.lifestyle.RuntimeLifeStyleManager;
import com.summit.core.runtime.workspace.Workspace;
import com.summit.core.tool.ToolExecuteCommand;
import com.summit.core.tool.ToolExecuteResult;
import com.summit.core.tool.ToolExecutionManager;
import com.summit.core.tool.ToolRegistry;
import com.summit.runtime.loop.control.InMemoryActiveExecutionRegistry;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletionException;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AgentLoopStepRunnerIntentionTest {

    @Test
    void templateAppendsGenericContextMessageForEverySuspension() {
        ExecutionControlSignal control = new ExecutionControlSignal("execution-1");
        control.requireSuspend();
        RecordingConversationManager conversations = new RecordingConversationManager();
        ActiveExecutionRegistry registry = new ActiveExecutionRegistry() {
            @Override
            public ExecutionControlSignal register(String executionId) {
                return control;
            }

            @Override
            public void unregister(ExecutionControlSignal signal) {
            }

            @Override
            public void requireSuspend(String executionId) {
            }

            @Override
            public void requireCancel(String executionId) {
            }
        };
        RuntimeLifeStyleManager lifeStyle = new RuntimeLifeStyleManager() {
            @Override public void onStart(Execution execution) { execution.start(); }
            @Override public void onCancel(Execution execution) { execution.cancel(); }
            @Override public void onSuspend(Execution execution) { execution.suspended(); }
            @Override public void onComplete(Execution execution) { execution.complete(); }
            @Override public void onError(Execution execution, Exception e) { execution.fail(e.getMessage()); }
            @Override public void onResume(Execution snapshot) { snapshot.resume(); }
        };
        RuntimeContext context = RuntimeContext.builder()
                .conversationManager(conversations)
                .activeExecutionRegistry(registry)
                .runtimeLifeStyleManager(lifeStyle)
                .build();

        new RuntimeProcessorTemplate(context).execute(executionWithContext());

        assertTrue(conversations.lastSystemMessage.contains("execution was suspended"));
        assertTrue(!conversations.lastSystemMessage.toLowerCase().contains("human"));
    }

    @Test
    void suspendsWithoutPersistingAnInFlightRound() throws Exception {
        InMemoryActiveExecutionRegistry registry = new InMemoryActiveExecutionRegistry();
        ExecutionControlSignal control = registry.register("execution-1");
        registry.requireSuspend("execution-1");
        AgentLoopStepRunner runner = new AgentLoopStepRunner(
                RuntimeContext.builder()
                        .build()
        );

        LoopResult result = runner.run(
                Execution.builder().id("execution-1").build(),
                control
        );

        assertEquals(LoopResult.Status.SUSPENDED, result.status());
        assertEquals(false, result.writeToolExecuted());
        registry.unregister(control);
    }

    @Test
    void cancellationTakesPriorityAndDoesNotPersistTerminalCheckpoint() throws Exception {
        InMemoryActiveExecutionRegistry registry = new InMemoryActiveExecutionRegistry();
        ExecutionControlSignal control = registry.register("execution-1");
        registry.requireSuspend("execution-1");
        registry.requireCancel("execution-1");
        AgentLoopStepRunner runner = new AgentLoopStepRunner(
                RuntimeContext.builder()
                        .build()
        );

        LoopResult result = runner.run(
                Execution.builder().id("execution-1").build(),
                control
        );

        assertEquals(LoopResult.Status.CANCELLED, result.status());
        assertEquals(false, result.writeToolExecuted());
        registry.unregister(control);
    }

    @Test
    void commitsTheCompleteToolBatchBeforeSuspendingOnPromise() throws Exception {
        ExecutionControlSignal control = new ExecutionControlSignal("execution-1");
        RecordingConversationManager conversations = new RecordingConversationManager();
        List<ToolExecuteResult> toolResults = List.of(
                ToolExecuteResult.success("first result"),
                ToolExecuteResult.promise("execution suspension requested")
        );

        AgentLoopStepRunner runner = new AgentLoopStepRunner(runtimeFor(conversations, toolResults, control, false));
        Execution execution = executionWithContext();

        LoopResult result = runner.run(execution, control);

        assertEquals(LoopResult.Status.SUSPENDED, result.status());
        assertEquals(1, conversations.committedRounds);
        assertSame(toolResults, conversations.lastToolResults);
    }

    @Test
    void terminationWinsAfterThePromisedToolBatchHasBeenCommitted() throws Exception {
        ExecutionControlSignal control = new ExecutionControlSignal("execution-1");
        RecordingConversationManager conversations = new RecordingConversationManager();
        List<ToolExecuteResult> toolResults = List.of(
                ToolExecuteResult.promise("execution suspension requested")
        );

        AgentLoopStepRunner runner = new AgentLoopStepRunner(runtimeFor(conversations, toolResults, control, true));

        LoopResult result = runner.run(executionWithContext(), control);

        assertEquals(LoopResult.Status.CANCELLED, result.status());
        assertEquals(1, conversations.committedRounds);
    }

    @Test
    void suspendsWhenTheModelCallIsInterruptedMidStream() throws Exception {
        ExecutionControlSignal control = new ExecutionControlSignal("execution-1");
        RecordingConversationManager conversations = new RecordingConversationManager();
        AgentLoopStepRunner runner = new AgentLoopStepRunner(
                runtimeForInterruptedStream(conversations, control, false, false));

        LoopResult result = runner.run(executionWithContext(), control);

        assertEquals(LoopResult.Status.SUSPENDED, result.status());
        // 中途被叫停的一轮不得提交：恢复后会重新发起该轮请求。
        assertEquals(0, conversations.committedRounds);
    }

    @Test
    void cancelsWhenTheModelCallIsInterruptedMidStreamForTermination() throws Exception {
        ExecutionControlSignal control = new ExecutionControlSignal("execution-1");
        RecordingConversationManager conversations = new RecordingConversationManager();
        AgentLoopStepRunner runner = new AgentLoopStepRunner(
                runtimeForInterruptedStream(conversations, control, true, false));

        LoopResult result = runner.run(executionWithContext(), control);

        assertEquals(LoopResult.Status.CANCELLED, result.status());
        assertEquals(0, conversations.committedRounds);
    }

    @Test
    void unwrapsCompletableFutureWrappingAroundAnInterruptedStream() throws Exception {
        ExecutionControlSignal control = new ExecutionControlSignal("execution-1");
        RecordingConversationManager conversations = new RecordingConversationManager();
        // 真实链路里流式调用等待的是 CompletableFuture，join() 会把中断信号包成 CompletionException；
        // 未解包时该异常会被当成普通失败，执行落到 FAILED 而不是 SUSPENDED。
        AgentLoopStepRunner runner = new AgentLoopStepRunner(
                runtimeForInterruptedStream(conversations, control, false, true));

        LoopResult result = runner.run(executionWithContext(), control);

        assertEquals(LoopResult.Status.SUSPENDED, result.status());
        assertEquals(0, conversations.committedRounds);
    }

    @Test
    void keepsReportingGenuineModelFailuresAsFailures() throws Exception {
        ExecutionControlSignal control = new ExecutionControlSignal("execution-1");
        RecordingConversationManager conversations = new RecordingConversationManager();
        AgentLoopStepRunner runner = new AgentLoopStepRunner(
                runtimeForInterruptedStream(conversations, control, false, false, new IllegalStateException("model exploded")));

        assertThrows(IllegalStateException.class, () -> runner.run(executionWithContext(), control));
    }

    /**
     * 模拟一次「模型仍在流式输出时收到控制请求」的调用：invoker 抛出
     * {@link ExecutionInterruptedException}，等价于流式 handler 提前结束 future 后
     * {@code join()} 的解包结果。{@code wrapped} 为 true 时按真实链路包一层
     * {@link CompletionException}。
     */
    private RuntimeContext runtimeForInterruptedStream(RecordingConversationManager conversations,
                                                       ExecutionControlSignal control,
                                                       boolean cancel,
                                                       boolean wrapped) {
        return runtimeForInterruptedStream(conversations, control, cancel, wrapped,
                new ExecutionInterruptedException(
                        cancel ? ExecutionInterruptedException.Kind.CANCEL
                               : ExecutionInterruptedException.Kind.SUSPEND,
                        "execution interrupted mid-stream"));
    }

    private RuntimeContext runtimeForInterruptedStream(RecordingConversationManager conversations,
                                                       ExecutionControlSignal control,
                                                       boolean cancel,
                                                       boolean wrapped,
                                                       Throwable failure) {
        RuntimeBoundaryChecker boundary = new RuntimeBoundaryChecker() {
            @Override
            public CheckPointResult before(Execution execution) {
                return CheckPointResult.continueWith();
            }

            @Override
            public CheckPointResult after(Execution execution) {
                return CheckPointResult.continueWith();
            }
        };
        ToolRegistry registry = new ToolRegistry(List.of());
        ToolExecutionManager tools = new ToolExecutionManager() {
            @Override
            public List<ToolExecuteResult> execute(ToolExecuteCommand command) {
                return List.of();
            }

            @Override
            public ToolRegistry toolRegistry() {
                return registry;
            }
        };
        return RuntimeContext.builder()
                .conversationManager(conversations)
                .toolExecutionManager(tools)
                .runtimeBoundaryChecker(boundary)
                .runtimeEventPublisher(new RuntimeEventPublisher(List.of()))
                .invoker(command -> {
                    // 请求在模型输出过程中到达，由流式 handler 捕获并中断调用。
                    if (cancel) control.requireCancel(); else control.requireSuspend();
                    RuntimeException signal = failure instanceof RuntimeException runtime
                            ? runtime
                            : new RuntimeException(failure);
                    if (wrapped) throw new CompletionException(signal);
                    throw signal;
                })
                .build();
    }

    private RuntimeContext runtimeFor(RecordingConversationManager conversations,
                                      List<ToolExecuteResult> toolResults,
                                      ExecutionControlSignal control,
                                      boolean cancelDuringTools) {
        ToolRegistry registry = new ToolRegistry(List.of());
        ToolExecutionManager tools = new ToolExecutionManager() {
            @Override
            public List<ToolExecuteResult> execute(ToolExecuteCommand command) {
                if (cancelDuringTools) control.requireCancel();
                return toolResults;
            }

            @Override
            public ToolRegistry toolRegistry() {
                return registry;
            }
        };
        RuntimeBoundaryChecker boundary = new RuntimeBoundaryChecker() {
            @Override
            public CheckPointResult before(Execution execution) {
                return CheckPointResult.continueWith();
            }

            @Override
            public CheckPointResult after(Execution execution) {
                return CheckPointResult.continueWith();
            }
        };
        ChatResponseEntity response = ChatResponseEntity.builder()
                .aiMessageEntity(AiMessageEntity.builder()
                        .text("")
                        .toolCalls(List.of(
                                new ToolCallRequest("call-1", "first", "{}", null),
                                new ToolCallRequest("call-2", "second", "{}", null)))
                        .build())
                .tokenUsage(TokenUsageEntity.empty())
                .build();
        return RuntimeContext.builder()
                .conversationManager(conversations)
                .toolExecutionManager(tools)
                .runtimeBoundaryChecker(boundary)
                .runtimeEventPublisher(new RuntimeEventPublisher(List.of()))
                .invoker(command -> response)
                .build();
    }

    private Execution executionWithContext() {
        return Execution.builder()
                .id("execution-1")
                .agentRequest(AgentRequest.builder().messages(List.of()).build())
                .messages(new ArrayList<>())
                .tokenUsage(TokenUsageEntity.empty())
                .build();
    }

    private static final class RecordingConversationManager implements ConversationManager {
        private int committedRounds;
        private List<ToolExecuteResult> lastToolResults;
        private String lastSystemMessage;

        @Override
        public void startConversation(Execution execution, Workspace workspace) {
        }

        @Override
        public void addMessage(Execution execution, ChatResponseEntity response,
                               List<ToolExecuteResult> toolResults) {
            committedRounds++;
            lastToolResults = toolResults;
        }

        @Override
        public List<Message> messages(Execution execution) {
            return execution.getMessages();
        }

        @Override
        public TokenUsageEntity tokenUsage(Execution execution) {
            return execution.getTokenUsage();
        }

        @Override
        public void rebuildContext(ContextSummary contextSummary, Execution execution) {
        }

        @Override
        public void appendSystemMessage(Execution execution, String text) {
            lastSystemMessage = text;
        }
    }
}
