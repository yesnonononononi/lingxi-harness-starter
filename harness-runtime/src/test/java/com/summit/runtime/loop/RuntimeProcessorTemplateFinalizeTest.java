package com.summit.runtime.loop;

import com.summit.core.agent.AgentRequest;
import com.summit.core.agent.ContextSpec;
import com.summit.core.agent.Execution;
import com.summit.core.agent.ExecutionState;
import com.summit.core.compact.ContextAttachmentProvider;
import com.summit.core.conversation.api.ChatResponseEntity;
import com.summit.core.conversation.event.ContextUpdateEvent;
import com.summit.core.conversation.event.ExecutionCompleteEvent;
import com.summit.core.conversation.event.RuntimeEventPublisher;
import com.summit.core.conversation.message.AiMessageEntity;
import com.summit.core.conversation.message.Message;
import com.summit.core.conversation.message.TokenUsageEntity;
import com.summit.core.conversation.message.UserMessageEntity;
import com.summit.core.mcp.McpToolScope;
import com.summit.core.model.chat.ChatModel;
import com.summit.core.runtime.RuntimeListener;
import com.summit.core.runtime.loop.ContextUsageReporter;
import com.summit.core.runtime.loop.ExecutionControlSignal;
import com.summit.core.runtime.loop.ExecutionRepository;
import com.summit.core.tool.ToolExecuteCommand;
import com.summit.core.tool.ToolExecuteResult;
import com.summit.core.tool.ToolExecutionManager;
import com.summit.core.tool.ToolRegistry;
import com.summit.core.transaction.RuntimeTransactionalSupport;
import com.summit.runtime.agent.AgentConfig;
import com.summit.runtime.audit.DefaultTokenAuditor;
import com.summit.runtime.audit.ExecutionTokenAuditRecorder;
import com.summit.runtime.context.RuntimeContext;
import com.summit.runtime.conversation.DefaultConversationManager;
import com.summit.runtime.conversation.DefaultTokenizer;
import com.summit.runtime.loop.control.InMemoryActiveExecutionRegistry;
import com.summit.runtime.model.DefaultChatModelInvoker;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.Callable;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The closing sequence of a run: one transaction around the snapshot, the control slot, the usage
 * broadcast and the terminal callback, and a terminal notification delivered only afterwards.
 */
class RuntimeProcessorTemplateFinalizeTest {

    private final List<String> trail = new ArrayList<>();

    @Test
    void snapshotAndControlSlotReleaseCommitTogetherAndTheTerminalEventWaits() {
        Execution result = runtime(new RecordingRepository(false), new RecordingSupport()).execute(execution());

        assertEquals(ExecutionState.COMPLETED, result.getExecutionState());

        int begin = trail.indexOf("tx:begin");
        int commit = trail.indexOf("tx:commit");
        assertTrue(begin >= 0 && commit > begin,
                "The finalize step must run inside exactly one transaction, actual: " + trail);
        assertEquals("save", trail.get(begin + 1),
                "The snapshot is what a rolled back finalize is allowed to discard first, actual: " + trail);
        assertTrue(inside(begin, commit, "unregister"),
                "Releasing the control slot belongs to the snapshot it closes, actual: " + trail);
        assertTrue(inside(begin, commit, "publish"),
                "The usage broadcast is part of the same durable step, actual: " + trail);
        assertTrue(trail.indexOf("complete") > commit,
                "A terminal notification must not be delivered before the commit, actual: " + trail);
    }

    @Test
    void oneRunClosesInOneTransaction() {
        runtime(new RecordingRepository(false), new RecordingSupport()).execute(execution());

        assertEquals(1, trail.stream().filter("tx:begin"::equals).count(),
                "Per-round work must not open the finalize transaction, actual: " + trail);
    }

    @Test
    void aFailedFinalizeRollsBackAsAWholeAndSuppressesTheTerminalEvent() {
        RecordingRepository repository = new RecordingRepository(true);

        RuntimeException thrown = assertThrows(RuntimeException.class,
                () -> runtime(repository, new RecordingSupport()).execute(execution()));

        assertInstanceOf(IllegalStateException.class, thrown.getCause(),
                "The primary finalize failure must not be replaced: " + thrown);
        assertTrue(trail.contains("tx:rollback"),
                "A failed finalize must leave the transaction rolled back, actual: " + trail);
        assertFalse(trail.contains("complete"),
                "A rolled-back finalize must not deliver the completion notification, actual: " + trail);
    }

    @Test
    void aContextBuiltWithoutTransactionSupportStillClosesTheRun() {
        Execution result = runtime(new RecordingRepository(false), null).execute(execution());

        assertEquals(ExecutionState.COMPLETED, result.getExecutionState());
        assertTrue(trail.contains("complete"), "A run without a transaction manager still closes, actual: " + trail);
        assertFalse(trail.contains("tx:begin"), "No transaction is opened when none was configured, actual: " + trail);
    }

    private boolean inside(int begin, int commit, String step) {
        int index = trail.lastIndexOf(step);
        return index > begin && index < commit;
    }

    private RuntimeProcessorTemplate runtime(RecordingRepository repository, RecordingSupport support) {
        RuntimeEventPublisher events = new RuntimeEventPublisher(List.of(new RuntimeListener() {
            @Override public int order() { return 0; }
            @Override
            public void onContextUpdate(ContextUpdateEvent event) {
                trail.add("publish");
            }

            @Override
            public void onExecutionCompleted(ExecutionCompleteEvent event) {
                trail.add("complete");
            }
        }));
        DefaultTokenizer tokenizer = new DefaultTokenizer();
        AgentConfig config = AgentConfig.builder().maxIterations(5).build();
        DefaultRuntimeLifeStyleManager lifecycle = new DefaultRuntimeLifeStyleManager(events,
                new ExecutionTokenAuditRecorder(new DefaultTokenAuditor(tokenizer, config,
                        new ToolRegistry(List.of()), McpToolScope.EMPTY), events));
        DefaultConversationManager conversations = new DefaultConversationManager(ContextAttachmentProvider.NONE);
        ToolExecutionManager tools = new ToolExecutionManager() {
            public List<ToolExecuteResult> execute(ToolExecuteCommand command) {
                return List.of(ToolExecuteResult.success("tool result"));
            }

            public ToolRegistry toolRegistry() {
                return new ToolRegistry(List.of());
            }
        };
        RuntimeContext.RuntimeContextBuilder<?, ?> context = RuntimeContext.builder()
                .conversationManager(conversations)
                .executionRepository(repository)
                .executionControl(new DefaultExecutionController(() -> null, repository, events, lifecycle))
                .runtimeLifeStyleManager(lifecycle).runtimeEventPublisher(events)
                .loopInterceptorProcessor(new DefaultLoopInterceptorProcessor(
                        List.of(new DefaultLoopInterceptor())))
                .runtimeBoundaryChecker(new BoundaryChecker(config, tokenizer, conversations, null, null, null, null))
                .toolExecutionManager(tools).invoker(new DefaultChatModelInvoker(model()))
                .usage(new ContextUsageReporter(tokenizer, 100_000, events, 1));
        if (support != null) context.runtimeTransactionalSupport(support);
        return new RuntimeProcessorTemplate(context.build());
    }

    private ChatModel model() {
        return request -> ChatResponseEntity.builder()
                .aiMessageEntity(AiMessageEntity.builder().text("answer").toolCalls(List.of()).build())
                .tokenUsage(TokenUsageEntity.of(3, 2, 1)).build();
    }

    private Execution execution() {
        List<Message> messages = List.of(UserMessageEntity.from("task"));
        return Execution.builder().id("finalize").executionState(ExecutionState.CREATED)
                .agentRequest(AgentRequest.builder().contextSpec(ContextSpec.builder().context(messages).build()).build())
                .messages(new ArrayList<>(messages)).tokenUsage(TokenUsageEntity.empty()).build();
    }

    /** Records the calls the closing sequence makes, in the order the runtime makes them. */
    private final class RecordingRepository implements ExecutionRepository {
        private final InMemoryActiveExecutionRegistry delegate = new InMemoryActiveExecutionRegistry();
        private final boolean failOnUnregister;

        private RecordingRepository(boolean failOnUnregister) {
            this.failOnUnregister = failOnUnregister;
        }

        public void save(Execution execution) {
            trail.add("save");
            delegate.save(execution);
        }

        public Optional<Execution> findById(String executionId) {
            return delegate.findById(executionId);
        }

        public ExecutionControlSignal register(String executionId) {
            return delegate.register(executionId);
        }

        public void unregister(ExecutionControlSignal signal) {
            trail.add("unregister");
            if (failOnUnregister) throw new IllegalStateException("control slot release failed");
            delegate.unregister(signal);
        }

        public void requireSuspend(String executionId) {
            delegate.requireSuspend(executionId);
        }

        public void requireCancel(String executionId) {
            delegate.requireCancel(executionId);
        }
    }

    /** A transaction that records its own boundaries; it wraps the callback exactly as Spring does. */
    private final class RecordingSupport implements RuntimeTransactionalSupport {
        @Override
        public <T> T execute(Callable<T> callback) {
            trail.add("tx:begin");
            try {
                T result = callback.call();
                trail.add("tx:commit");
                return result;
            } catch (RuntimeException e) {
                trail.add("tx:rollback");
                throw e;
            } catch (Exception e) {
                trail.add("tx:rollback");
                throw new IllegalStateException(e);
            }
        }
    }
}
