package com.summit.runtime;

import com.summit.core.agent.AgentRequest;
import com.summit.core.agent.AgentRuntimeParameters;
import com.summit.core.agent.Execution;
import com.summit.core.conversation.api.ChatResponseEntity;
import com.summit.core.conversation.api.ConversationTranscriptSink;
import com.summit.core.conversation.message.ToolMessageEntity;
import com.summit.core.conversation.message.TokenUsageEntity;
import com.summit.core.conversation.api.ToolCallRequest;
import com.summit.runtime.context.RuntimeContext;
import com.summit.core.conversation.event.*;
import com.summit.core.conversation.message.AiMessageEntity;
import com.summit.core.conversation.message.UserMessageEntity;
import com.summit.core.model.ModelChatCommand;
import com.summit.core.model.streaming.StreamingHandler;
import com.summit.core.runtime.RuntimeEnvironment;
import com.summit.core.runtime.RuntimeListener;
import com.summit.core.runtime.loop.ContextUsageReporter;
import com.summit.core.runtime.workspace.Workspace;
import com.summit.core.tool.*;
import com.summit.runtime.conversation.DefaultConversationManager;
import com.summit.runtime.conversation.DefaultTokenizer;
import com.summit.core.compact.ContextAttachmentProvider;
import com.summit.runtime.loop.DefaultRuntimeLifeStyleManager;
import com.summit.runtime.model.ModelRequestFactory;
import com.summit.runtime.tool.DefaultToolExecutionManager;
import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;

class EventMetadataPropagationTest {
    private static final Map<String, Object> METADATA = Map.of("turnId", "9007199254740995");
    private final CapturingListener listener = new CapturingListener();
    private final RuntimeEventPublisher publisher = new RuntimeEventPublisher(List.of(listener));

    /** 本用例不涉及中断，传一个不触碰传输层的空句柄，而不是把 null 当作合法参数。 */
    private static final StreamingHandler NOOP_TRANSPORT = new StreamingHandler() {
        @Override
        public void cancel() {
        }

        @Override
        public boolean isCancelled() {
            return false;
        }
    };

    @Test
    void lifecycleAndContextEventsCarrySelectedMetadata() {
        Execution execution = execution("run", METADATA);
        DefaultRuntimeLifeStyleManager lifecycle = new DefaultRuntimeLifeStyleManager(publisher);
        lifecycle.onStart(execution);
        lifecycle.onSuspend(execution);
        lifecycle.onResume(execution);
        lifecycle.onComplete(execution);
        lifecycle.onCancel(execution);
        lifecycle.onError(execution, new IllegalStateException("failed"));
        ContextUsageReporter usage = new ContextUsageReporter(new DefaultTokenizer(), 1000, publisher, 1);
        for (ContextUpdateEvent.Phase phase : ContextUpdateEvent.Phase.values()) {
            usage.publish(execution, phase, "");
        }

        assertEquals(9, listener.events.size());
        listener.events.forEach(event -> assertMetadata(event, METADATA));
    }

    @Test
    void modelFactoryCarriesMetadataToTextThinkingAndCompleteCallbacks() {
        Execution execution = execution("stream", METADATA);
        execution.setStreaming(true);
        ToolExecutionManager tools = new ToolExecutionManager() {
            public List<ToolExecuteResult> execute(ToolExecuteCommand command) { return List.of(); }
            public ToolRegistry toolRegistry() { return new ToolRegistry(List.of()); }
        };
        RuntimeContext context = RuntimeContext.builder()
                .runtimeEventPublisher(publisher).toolExecutionManager(tools)
                .conversationManager(new DefaultConversationManager(
                        (id, ai, toolMessages) -> { }, ContextAttachmentProvider.NONE))
                .build();
        ModelChatCommand command = new ModelRequestFactory(context).build(execution, List.of(), "1234567890123456789", null);
        command.streamingChatResponseHandler().onPartialResponse("hello", NOOP_TRANSPORT);
        command.streamingChatResponseHandler().onPartialThinking("reason", NOOP_TRANSPORT);
        command.streamingChatResponseHandler().onFinalResponse(ChatResponseEntity.builder()
                .aiMessageEntity(AiMessageEntity.builder().text("answer").build()).build());

        assertEquals(3, listener.events.size());
        listener.events.forEach(event -> assertMetadata(event, METADATA));
    }

    @Test
    void concurrentToolExecutionsAndFailureEventsKeepTheirOwnMetadata() {
        List<Map<String, Object>> received = new CopyOnWriteArrayList<>();
        ToolDefinition<?> tool = ToolDefinition.builder().id("test").name("test")
                .timeout(0L).maxOutput(1000).concurrentPolicy(ConcurrentPolicy.READ_ONLY)
                .executor(execution -> {
                    received.add(execution.getEventMetaData());
                    if ("fail".equals(execution.getArgs())) {
                        throw new IllegalStateException("failed");
                    }
                    return ToolExecuteResult.success("ok");
                }).build();
        ToolExecutionContext context = ToolExecutionContext.builder()
                .toolRegistry(new ToolRegistry(List.of(tool))).runtimeEventPublisher(publisher)
                .concurrentToolLimit(2).build();
        Map<String, Object> otherMetadata = Map.of("turnId", "other-turn");
        try (DefaultToolExecutionManager manager = new DefaultToolExecutionManager(context,
                invocation -> invocation.getMethod().invoke(invocation.getTarget(), invocation.getContext()),
                List.of())) {
            CompletableFuture<List<ToolExecuteResult>> first = CompletableFuture.supplyAsync(
                    () -> manager.execute(command("first", METADATA)));
            CompletableFuture<List<ToolExecuteResult>> second = CompletableFuture.supplyAsync(
                    () -> manager.execute(command("second", otherMetadata)));
            first.join();
            second.join();
        }

        // Each run has a success, a failure and a rejected unknown-tool call.
        assertEquals(10, listener.events.size());
        assertEquals(4, received.size());
        assertTrue(received.contains(METADATA));
        assertTrue(received.contains(otherMetadata));
        listener.events.forEach(event -> assertMetadata(event,
                "first".equals(event.executionId()) ? METADATA : otherMetadata));
    }

    @Test
    void eventsSnapshotMetadataAndLegacyConstructorsUseEmptyMetadata() {
        Map<String, Object> mutable = new HashMap<>(METADATA);
        ExecutionStartEvent event = new ExecutionStartEvent("run", mutable);
        mutable.put("turnId", "changed");

        assertEquals(METADATA, event.eventMetaData());
        assertThrows(UnsupportedOperationException.class,
                () -> event.eventMetaData().put("turnId", "changed"));
        assertEquals(Map.of(), new ExecutionStartEvent("legacy").eventMetaData());
        assertEquals(Map.of(), AgentPartialTextEvent.builder().executionId("legacy").build().eventMetaData());
        assertEquals(Map.of(), AgentCompleteTextEvent.builder().executionId("legacy").build().eventMetaData());
    }

    @Test
    void acceptedModelRoundPassesMetadataToTranscriptSink() {
        AtomicReference<Map<String, Object>> received = new AtomicReference<>();
        ConversationTranscriptSink sink = new ConversationTranscriptSink() {
            public void appendRound(String id, AiMessageEntity ai, List<ToolMessageEntity> tools) {
                fail("metadata-aware overload must be used");
            }

            public void appendRound(String id, AiMessageEntity ai, List<ToolMessageEntity> tools,
                                    Map<String, Object> metadata) {
                received.set(metadata);
            }
        };
        DefaultConversationManager conversations = new DefaultConversationManager(sink, ContextAttachmentProvider.NONE);
        Execution execution = execution("transcript", METADATA);
        execution.setTokenUsage(TokenUsageEntity.empty());

        conversations.addMessage(execution, ChatResponseEntity.builder()
                .aiMessageEntity(AiMessageEntity.builder().text("answer").build())
                .tokenUsage(TokenUsageEntity.empty()).build(), List.of());

        assertEquals(METADATA, received.get());
    }

    @Test
    void requestBuilderSnapshotsSelectedMetadata() {
        Map<String, Object> mutable = new HashMap<>(METADATA);
        AgentRuntimeParameters parameters = AgentRuntimeParameters.builder().eventMetaData(mutable).build();
        mutable.put("turnId", "changed");

        assertEquals(METADATA, parameters.getEventMetaData());
    }
    private void assertMetadata(AgentEvent event, Map<String, Object> expected) {
        assertEquals(expected, event.eventMetaData(), event.type());
        assertFalse(event.eventMetaData().containsKey("private"), event.type());
    }

    private Execution execution(String id, Map<String, Object> metadata) {
        AgentRequest request = AgentRequest.builder().executionId(id)
                .messages(List.of(UserMessageEntity.from("task")))
                .runtimeParameters(AgentRuntimeParameters.builder()
                        .attributes(Map.of("private", "internal"))
                        .eventMetaData(metadata).build()).build();
        return Execution.create(request, "agent");
    }

    private ToolExecuteCommand command(String id, Map<String, Object> metadata) {
        return new ToolExecuteCommand(List.of(
                new ToolCallRequest(id + "-success", "test", 0, "{}"),
                new ToolCallRequest(id + "-failure", "test", 1, "fail"),
                new ToolCallRequest(id + "-unknown", "missing", 2, "{}")),
                id, workspace(), Map.of("private", "internal"), metadata, List.of("test"), "1234567890123456789", false, null);
    }

    private Workspace workspace() {
        return new Workspace() {
            public String id() { return "workspace"; }
            public RuntimeEnvironment runtimeEnvironment() { return null; }
            public String workDir() { return "."; }
            public Path resolve(String path) { return Path.of(path); }
        };
    }

    private static class CapturingListener implements RuntimeListener {
        private final List<AgentEvent> events = new CopyOnWriteArrayList<>();
        public void onExecutionStart(ExecutionStartEvent event) { events.add(event); }
        public void onExecutionSuspended(ExecutionSuspendedEvent event) { events.add(event); }
        public void onExecutionResumed(ExecutionResumedEvent event) { events.add(event); }
        public void onExecutionCompleted(ExecutionCompleteEvent event) { events.add(event); }
        public void onExecutionCancelled(ExecutionCancelledEvent event) { events.add(event); }
        public void onExecutionError(ExecutionErrorEvent event) { events.add(event); }
        public void onPartialText(AgentPartialTextEvent event) { events.add(event); }
        public void onPartialThinking(AgentPartialThinkingEvent event) { events.add(event); }
        public void onCompleteText(AgentCompleteTextEvent event) { events.add(event); }
        public void onContextUpdate(ContextUpdateEvent event) { events.add(event); }
        public void onToolCall(ToolCallStartEvent event) { events.add(event); }
        public void onToolCallOutput(ToolCallEndEvent event) { events.add(event); }
    }
}
