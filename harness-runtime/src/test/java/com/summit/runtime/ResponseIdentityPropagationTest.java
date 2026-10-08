package com.summit.runtime;

import com.summit.core.agent.AgentRequest;
import com.summit.core.agent.Execution;
import com.summit.core.compact.ContextAttachmentProvider;
import com.summit.core.conversation.api.ChatResponseEntity;
import com.summit.core.conversation.api.ConversationTranscriptSink;
import com.summit.core.conversation.api.ToolCallRequest;
import com.summit.core.conversation.event.AgentCompleteTextEvent;
import com.summit.core.conversation.event.AgentMessageEvent;
import com.summit.core.conversation.event.AgentPartialTextEvent;
import com.summit.core.conversation.event.AgentPartialThinkingEvent;
import com.summit.core.conversation.event.RuntimeEventPublisher;
import com.summit.core.conversation.event.ToolCallEndEvent;
import com.summit.core.conversation.event.ToolCallStartEvent;
import com.summit.core.conversation.message.AiMessageEntity;
import com.summit.core.conversation.message.Message;
import com.summit.core.conversation.message.ToolMessageEntity;
import com.summit.core.conversation.message.TokenUsageEntity;
import com.summit.core.conversation.message.UserMessageEntity;
import com.summit.core.model.ModelChatCommand;
import com.summit.core.model.streaming.StreamingHandler;
import com.summit.core.runtime.RuntimeEnvironment;
import com.summit.core.runtime.RuntimeListener;
import com.summit.core.runtime.loop.ExecutionControlSignal;
import com.summit.core.runtime.loop.LoopResult;
import com.summit.core.runtime.workspace.Workspace;
import com.summit.core.tool.ConcurrentPolicy;
import com.summit.core.tool.ToolCallStatus;
import com.summit.core.tool.ToolDefinition;
import com.summit.core.tool.ToolExecuteCommand;
import com.summit.core.tool.ToolExecuteResult;
import com.summit.core.tool.ToolExecution;
import com.summit.core.tool.ToolExecutionContext;
import com.summit.core.tool.ToolExecutionPolicy;
import com.summit.core.tool.ToolRegistry;
import com.summit.core.tool.ToolResultType;
import com.summit.runtime.agent.AgentConfig;
import com.summit.runtime.context.RuntimeContext;
import com.summit.runtime.conversation.DefaultConversationManager;
import com.summit.runtime.conversation.DefaultTokenizer;
import com.summit.runtime.loop.AgentLoopStepRunner;
import com.summit.runtime.loop.BoundaryChecker;
import com.summit.runtime.loop.DefaultLoopInterceptor;
import com.summit.runtime.loop.DefaultLoopInterceptorProcessor;
import com.summit.runtime.model.ModelRequestFactory;
import com.summit.runtime.model.StreamingModelResponseBehaveDecider;
import com.summit.runtime.tool.DefaultToolExecutionManager;
import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;

class ResponseIdentityPropagationTest {
    private static final Map<String, Object> METADATA = Map.of("turnId", "turn-1");
    private static final StreamingHandler TRANSPORT = new StreamingHandler() {
        public void cancel() { }
        public boolean isCancelled() { return false; }
    };

    @Test
    void streamedEventsAndCompletedResponseShareTheRuntimeIdentity() {
        try (Fixture fixture = new Fixture(ToolExecuteResult.success("ok"), List.of())) {
            fixture.execution.setStreaming(true);
            UUID responseId = UUID.randomUUID();
            ModelChatCommand command = new ModelRequestFactory(fixture.context)
                    .build(fixture.execution, List.of("test"), responseId, null);
            StreamingModelResponseBehaveDecider handler = assertInstanceOf(
                    StreamingModelResponseBehaveDecider.class, command.streamingChatResponseHandler());
            handler.onPartialThinking("reason", TRANSPORT);
            handler.onPartialResponse("answer", TRANSPORT);
            ChatResponseEntity response = response(false);
            handler.onFinalResponse(response);

            assertEquals(List.of(responseId, responseId, responseId), fixture.events.streamedIds);
            assertSame(response, handler.getStreamingResponseContext().future().join());
            assertEquals(responseId, response.getResponseId());
            assertEquals("provider-response", response.getMeta().getId());
        }
    }

    @Test
    void eachModelInvocationHasItsOwnIdentityAcrossEventsToolsAndTranscript() throws Exception {
        try (Fixture fixture = new Fixture(ToolExecuteResult.success("ok"), List.of())) {
            assertEquals(LoopResult.Status.COMPLETED, fixture.run().status());
            assertEquals(2, fixture.events.messages.size());
            UUID first = fixture.events.messages.getFirst().getResponseId();
            UUID second = fixture.events.messages.getLast().getResponseId();
            assertNotNull(first);
            assertNotNull(second);
            assertNotEquals(first, second);
            assertEquals(List.of(first, second), fixture.rounds);
            assertEquals(first, fixture.policyExecutions.getFirst().getResponseId());
            assertEquals(first, fixture.events.starts.getFirst().getResponseId());
            assertEquals(first, fixture.events.ends.getFirst().getResponseId());
            assertEquals("call-1", fixture.events.starts.getFirst().getRequestId());
            assertEquals("call-1", fixture.events.ends.getFirst().getRequestId());
        }
    }

    @Test
    void promisedToolKeepsItsOriginIdentityWhenTheLoopStartsAnotherInvocation() throws Exception {
        try (Fixture fixture = new Fixture(ToolExecuteResult.success("unused"),
                List.of(call -> ToolExecuteResult.promise("waiting")))) {
            assertEquals(LoopResult.Status.SUSPENDED, fixture.run().status());
            UUID original = fixture.events.messages.getFirst().getResponseId();
            ToolExecution promised = fixture.policyExecutions.getFirst();
            assertNotNull(original);
            assertEquals(original, promised.getResponseId());
            assertEquals(original, fixture.rounds.getFirst());
            assertEquals(original, fixture.events.ends.getFirst().getResponseId());
            assertEquals(ToolCallStatus.PROMISED, fixture.events.ends.getFirst().resultStatus());

            assertEquals(LoopResult.Status.COMPLETED, fixture.run().status());
            UUID resumed = fixture.events.messages.getLast().getResponseId();
            assertNotEquals(original, resumed);
            assertEquals(List.of(original, resumed), fixture.rounds);
            assertEquals(original, promised.getResponseId());
        }
    }

    @Test
    void compactionRoundRetainsItsIdentityInTheTranscript() throws Exception {
        try (Fixture fixture = new Fixture(ToolExecuteResult.success(
                "{\"summary\":\"finished earlier work\"}", ToolResultType.CONTEXT_COMPACT), List.of())) {
            assertEquals(LoopResult.Status.COMPLETED, fixture.run().status());
            List<UUID> identities = fixture.events.messages.stream().map(AgentMessageEvent::getResponseId).toList();
            assertEquals(2, identities.size());
            assertNotNull(identities.getFirst());
            assertEquals(identities, fixture.rounds);
            assertFalse(fixture.execution.getMessages().stream().anyMatch(ToolMessageEntity.class::isInstance));
        }
    }

    @Test
    void concurrentBatchesKeepIdentityOnSuccessFailureAndRejection() {
        try (Fixture fixture = new Fixture(ToolExecuteResult.success("ok"), List.of())) {
            UUID first = UUID.randomUUID();
            UUID second = UUID.randomUUID();
            CompletableFuture<Void> one = CompletableFuture.runAsync(() -> fixture.tools.execute(command("first", first)));
            CompletableFuture<Void> two = CompletableFuture.runAsync(() -> fixture.tools.execute(command("second", second)));
            CompletableFuture.allOf(one, two).join();

            assertEquals(6, fixture.events.ends.size());
            assertEquals(4, fixture.policyExecutions.size());
            Map<String, UUID> expected = Map.of("first", first, "second", second);
            for (ToolExecution call : fixture.policyExecutions) {
                assertEquals(expected.get(call.getExecutionId()), call.getResponseId());
            }
            for (ToolCallStartEvent event : fixture.events.starts) {
                assertEquals(expected.get(event.executionId()), event.getResponseId());
            }
            for (ToolCallEndEvent event : fixture.events.ends) {
                assertEquals(expected.get(event.executionId()), event.getResponseId());
            }
            assertEquals(2, fixture.events.ends.stream().filter(event -> event.resultStatus() == ToolCallStatus.FAILED).count());
            assertEquals(2, fixture.events.ends.stream().filter(event -> event.resultStatus() == ToolCallStatus.REJECTED).count());
        }
    }

    @Test
    void legacyTranscriptSinksStillReceiveRoundsAndSelectedMetadata() {
        AtomicInteger legacyCalls = new AtomicInteger();
        ConversationTranscriptSink legacy = (id, ai, tools) -> legacyCalls.incrementAndGet();
        UUID responseId = UUID.randomUUID();
        legacy.appendRound("legacy", AiMessageEntity.builder().text("answer").build(), List.of(), responseId, METADATA);
        assertEquals(1, legacyCalls.get());

        AtomicReference<Map<String, Object>> metadata = new AtomicReference<>();
        ConversationTranscriptSink selected = new ConversationTranscriptSink() {
            public void appendRound(String id, AiMessageEntity ai, List<ToolMessageEntity> tools) {
                fail("The metadata-aware overload must still be used");
            }
            public void appendRound(String id, AiMessageEntity ai, List<ToolMessageEntity> tools, Map<String, Object> value) {
                metadata.set(value);
            }
        };
        selected.appendRound("selected", AiMessageEntity.builder().text("answer").build(), List.of(), responseId, METADATA);
        assertEquals(METADATA, metadata.get());
    }

    private static ChatResponseEntity response(boolean tools) {
        return ChatResponseEntity.builder().aiMessageEntity(AiMessageEntity.builder().text("answer")
                .toolCalls(tools ? List.of(new ToolCallRequest("call-1", "test", "{}")) : List.of()).build())
                .meta(ChatResponseEntity.Meta.builder().id("provider-response").build())
                .tokenUsage(TokenUsageEntity.of(3, 2, 1)).build();
    }

    private static ToolExecuteCommand command(String executionId, UUID responseId) {
        return new ToolExecuteCommand(List.of(new ToolCallRequest(executionId + "-ok", "test", "{}"),
                new ToolCallRequest(executionId + "-fail", "test", "fail"),
                new ToolCallRequest(executionId + "-missing", "missing", "{}")),
                executionId, workspace(), Map.of(), METADATA, List.of("test"), responseId, false, null);
    }

    private static Workspace workspace() {
        return new Workspace() {
            public String id() { return "workspace"; }
            public RuntimeEnvironment runtimeEnvironment() { return null; }
            public String workDir() { return "."; }
            public Path resolve(String path) { return Path.of(path); }
        };
    }

    private static final class Fixture implements AutoCloseable {
        private final Capture events = new Capture();
        private final List<UUID> rounds = new ArrayList<>();
        private final List<ToolExecution> policyExecutions = new CopyOnWriteArrayList<>();
        private final DefaultToolExecutionManager tools;
        private final RuntimeContext context;
        private final Execution execution;

        private Fixture(ToolExecuteResult result, List<ToolExecutionPolicy> policies) {
            List<Message> messages = List.of(UserMessageEntity.from("task"));
            AgentRequest request = AgentRequest.builder().messages(messages).toolList(List.of("test")).build();
            request.runtimeParametersOrDefault().setEventMetaData(METADATA);
            execution = Execution.builder().id("execution-1").agentId("agent-1").agentRequest(request)
                    .messages(new ArrayList<>(messages)).tokenUsage(TokenUsageEntity.empty()).build();
            ConversationTranscriptSink sink = new ConversationTranscriptSink() {
                public void appendRound(String id, AiMessageEntity ai, List<ToolMessageEntity> toolMessages) {
                    fail("The response-aware overload must be used");
                }
                public void appendRound(String id, AiMessageEntity ai, List<ToolMessageEntity> toolMessages,
                                        UUID responseId, Map<String, Object> metadata) {
                    assertEquals(METADATA, metadata);
                    rounds.add(responseId);
                }
            };
            DefaultConversationManager conversations = new DefaultConversationManager(sink, ContextAttachmentProvider.NONE);
            RuntimeEventPublisher publisher = new RuntimeEventPublisher(List.of(events));
            ToolDefinition<?> definition = ToolDefinition.builder().id("test").name("test").timeout(0L)
                    .maxOutput(1000).concurrentPolicy(ConcurrentPolicy.READ_ONLY).executor(call -> {
                        if ("fail".equals(call.getArgs())) throw new IllegalStateException("tool failed");
                        return result;
                    }).build();
            List<ToolExecutionPolicy> chain = new ArrayList<>();
            chain.add(call -> { policyExecutions.add(call); return null; });
            chain.addAll(policies);
            tools = new DefaultToolExecutionManager(ToolExecutionContext.builder()
                    .toolRegistry(new ToolRegistry(List.of(definition))).runtimeEventPublisher(publisher).build(),
                    invocation -> invocation.getMethod().invoke(invocation.getTarget(), invocation.getContext()), chain);
            AtomicInteger calls = new AtomicInteger();
            context = RuntimeContext.builder().conversationManager(conversations).workspace(workspace())
                    .runtimeEventPublisher(publisher).toolExecutionManager(tools)
                    .loopInterceptorProcessor(new DefaultLoopInterceptorProcessor(List.of(new DefaultLoopInterceptor())))
                    .runtimeBoundaryChecker(new BoundaryChecker(AgentConfig.builder().maxIterations(4).build(),
                            new DefaultTokenizer(), conversations, null, null))
                    .invoker(command -> response(calls.getAndIncrement() == 0)).build();
        }

        private LoopResult run() throws Exception {
            return new AgentLoopStepRunner(context).run(execution, new ExecutionControlSignal(execution.getId()));
        }

        public void close() { tools.close(); }
    }

    private static final class Capture implements RuntimeListener {
        private final List<UUID> streamedIds = new ArrayList<>();
        private final List<AgentMessageEvent> messages = new CopyOnWriteArrayList<>();
        private final List<ToolCallStartEvent> starts = new CopyOnWriteArrayList<>();
        private final List<ToolCallEndEvent> ends = new CopyOnWriteArrayList<>();
        public void onPartialThinking(AgentPartialThinkingEvent event) { streamedIds.add(event.responseId()); }
        public void onPartialText(AgentPartialTextEvent event) { streamedIds.add(event.responseId()); }
        public void onCompleteText(AgentCompleteTextEvent event) { streamedIds.add(event.responseId()); }
        public void onAiMessage(AgentMessageEvent event) { messages.add(event); }
        public void onToolCall(ToolCallStartEvent event) { starts.add(event); }
        public void onToolCallOutput(ToolCallEndEvent event) { ends.add(event); }
    }
}
