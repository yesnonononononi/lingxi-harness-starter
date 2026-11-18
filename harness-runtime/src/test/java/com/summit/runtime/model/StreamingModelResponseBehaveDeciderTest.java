package com.summit.runtime.model;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.summit.core.conversation.api.ChatResponseEntity;
import com.summit.core.conversation.event.AgentPartialTextEvent;
import com.summit.core.conversation.event.AgentPartialThinkingEvent;
import com.summit.core.conversation.event.RuntimeEventPublisher;
import com.summit.core.conversation.event.ToolCallPrepareEvent;
import com.summit.core.conversation.message.AiMessageEntity;
import com.summit.core.conversation.message.TokenUsageEntity;
import com.summit.core.exception.ResponseTimeoutException;
import com.summit.core.json.ExecutionJson;
import com.summit.core.model.streaming.StreamingHandler;
import com.summit.core.runtime.RuntimeListener;
import com.summit.core.runtime.loop.ExecutionControlSignal;
import com.summit.core.runtime.loop.suspension.ExecutionInterruptedException;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.ArrayList;
import com.summit.core.conversation.event.AgentEventPublisher;
import com.summit.core.runtime.AgentStreamListener;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 流式回调中的过程中断契约。
 *
 * <p>控制信号只在循环边界被观察，而一次流式模型调用可能持续很久。若信号在调用进行中到达，
 * handler 必须在收到下一个回调时立即结束 future、关闭底层传输句柄，让阻塞在 {@code join()} 上的
 * 调用方拿回控制权，而不是等模型把用户已经要求放弃的内容全部输出完。</p>
 */
class StreamingModelResponseBehaveDeciderTest {

    @Test
    void publishesIndependentUtf16StartOffsetsForTextAndThinking() throws Exception {
        RecordingListener listener = new RecordingListener();
        StreamingModelResponseBehaveDecider handler = decider(
                new CompletableFuture<>(), null, new AgentEventPublisher(List.of(listener)));
        RecordingTransport transport = new RecordingTransport();

        handler.onPartialResponse("A\uD83D\uDE00", transport);
        handler.onPartialThinking("\uD83D\uDE00", transport);
        handler.onPartialResponse("", transport);
        handler.onPartialThinking("why", transport);
        handler.onPartialResponse("B", transport);
        handler.onPartialThinking("?", transport);

        assertEquals(List.of(0, 3, 3), listener.textEvents.stream()
                .map(AgentPartialTextEvent::offset).toList());
        assertEquals(List.of(0, 2, 5), listener.thinkingEvents.stream()
                .map(AgentPartialThinkingEvent::offset).toList());
        assertEquals("A\uD83D\uDE00B", listener.textEvents.stream()
                .map(AgentPartialTextEvent::content).reduce("", String::concat));

        ObjectMapper mapper = ExecutionJson.newObjectMapper();
        JsonNode textJson = mapper.readTree(mapper.writeValueAsString(listener.textEvents.get(2)));
        JsonNode thinkingJson = mapper.readTree(mapper.writeValueAsString(listener.thinkingEvents.get(1)));
        assertTrue(textJson.get("offset").isIntegralNumber());
        assertEquals(3, textJson.get("offset").intValue());
        assertEquals("B", textJson.get("content").textValue());
        assertTrue(thinkingJson.get("offset").isIntegralNumber());
        assertEquals(2, thinkingJson.get("offset").intValue());
    }

    @Test
    void newResponseHandlerStartsBothOffsetsAtZero() {
        RecordingListener listener = new RecordingListener();
        AgentEventPublisher publisher = new AgentEventPublisher(List.of(listener));
        RecordingTransport transport = new RecordingTransport();
        StreamingModelResponseBehaveDecider first = decider(new CompletableFuture<>(), null, publisher);
        first.onPartialResponse("previous text", transport);
        first.onPartialThinking("previous thinking", transport);

        StreamingModelResponseBehaveDecider next = new StreamingModelResponseBehaveDecider(
                new RuntimeEventPublisher(List.of()),
                publisher, StreamingModelResponseBehaveDecider.StreamingResponseContext.builder()
                        .executionId("execution-1").agentId("chatAgent").responseId("1234567890123456790")
                        .future(new CompletableFuture<>()).build(), null);
        next.onPartialResponse("next text", transport);
        next.onPartialThinking("next thinking", transport);

        assertEquals(0, listener.textEvents.get(1).offset());
        assertEquals(0, listener.thinkingEvents.get(1).offset());
        assertEquals("1234567890123456790", listener.textEvents.get(1).responseId());
        assertEquals("1234567890123456790", listener.thinkingEvents.get(1).responseId());
    }

    @Test
    void finalResponseObservesCancellationEvenWithoutAnotherDelta() {
        var future = new CompletableFuture<ChatResponseEntity>();
        var control = new ExecutionControlSignal("execution-1");
        var handler = decider(future, control);

        control.requireCancel();
        handler.onFinalResponse(response("late answer"));

        var error = assertThrows(ExecutionException.class, future::get);
        assertEquals(ExecutionInterruptedException.Kind.CANCEL,
                assertInstanceOf(ExecutionInterruptedException.class, error.getCause()).kind());
    }

    @Test
    void suspendRequestDuringStreamAbortsThePendingCall() {
        CompletableFuture<ChatResponseEntity> future = new CompletableFuture<>();
        ExecutionControlSignal control = new ExecutionControlSignal("execution-1");
        StreamingModelResponseBehaveDecider handler = decider(future, control);
        RecordingTransport transport = new RecordingTransport();

        handler.onPartialResponse("partial", transport);

        control.requireSuspend();
        handler.onPartialThinking("more thinking", transport);

        assertTrue(future.isCompletedExceptionally());
        ExecutionException thrown = assertThrows(ExecutionException.class, future::get);
        ExecutionInterruptedException interrupt =
                assertInstanceOf(ExecutionInterruptedException.class, thrown.getCause());
        assertEquals(ExecutionInterruptedException.Kind.SUSPEND, interrupt.kind());
        assertEquals(1, transport.cancelCount(), "中断后必须关闭底层流，而不是等它自己排空");
    }

    @Test
    void cancelRequestDuringStreamAbortsAsCancellation() {
        CompletableFuture<ChatResponseEntity> future = new CompletableFuture<>();
        ExecutionControlSignal control = new ExecutionControlSignal("execution-1");
        StreamingModelResponseBehaveDecider handler = decider(future, control);
        RecordingTransport transport = new RecordingTransport();

        control.requireCancel();
        handler.onPartialResponse("partial", transport);

        ExecutionException thrown = assertThrows(ExecutionException.class, future::get);
        ExecutionInterruptedException interrupt =
                assertInstanceOf(ExecutionInterruptedException.class, thrown.getCause());
        assertEquals(ExecutionInterruptedException.Kind.CANCEL, interrupt.kind());
        assertEquals(1, transport.cancelCount());
    }

    @Test
    void finalResponseIsDiscardedOnceTheStreamHasBeenInterrupted() {
        CompletableFuture<ChatResponseEntity> future = new CompletableFuture<>();
        ExecutionControlSignal control = new ExecutionControlSignal("execution-1");
        StreamingModelResponseBehaveDecider handler = decider(future, control);

        control.requireSuspend();
        handler.onPartialResponse("partial", new RecordingTransport());
        // 传输层仍在后台排空，晚到的完整响应不得覆盖中断结果。
        handler.onFinalResponse(response("late complete answer"));

        assertTrue(future.isCompletedExceptionally());
    }

    @Test
    void streamCompletesNormallyWithoutAControlRequest() throws Exception {
        CompletableFuture<ChatResponseEntity> future = new CompletableFuture<>();
        ExecutionControlSignal control = new ExecutionControlSignal("execution-1");
        StreamingModelResponseBehaveDecider handler = decider(future, control);
        RecordingTransport transport = new RecordingTransport();

        handler.onPartialResponse("hello", transport);
        handler.onFinalResponse(response("hello world"));

        assertEquals("hello world", future.get().getAiMessageEntity().text());
        assertFalse(future.isCompletedExceptionally());
        assertEquals(0, transport.cancelCount(), "没有中断请求时不得触碰传输层");
    }

    @Test
    void handlerWithoutAControlSignalNeverInterrupts() throws Exception {
        CompletableFuture<ChatResponseEntity> future = new CompletableFuture<>();
        RecordingTransport transport = new RecordingTransport();
        StreamingModelResponseBehaveDecider handler = decider(future, null);

        handler.onPartialResponse("hello", transport);
        handler.onFinalResponse(response("done"));

        assertEquals("done", future.get().getAiMessageEntity().text());
        assertEquals(0, transport.cancelCount());
    }

    @Test
    void errorDoesNotOverwriteAnAlreadyCompletedStream() throws Exception {
        CompletableFuture<ChatResponseEntity> future = new CompletableFuture<>();
        StreamingModelResponseBehaveDecider handler = decider(future, null);

        handler.onFinalResponse(response("ok"));
        handler.onError(new IllegalStateException("late transport failure"));

        assertFalse(future.isCompletedExceptionally());
        assertEquals("ok", future.get().getAiMessageEntity().text());
    }

    /**
     * 中断命中的那个 delta 必须被丢弃：只结束 future 而仍把内容发布出去，前端会看到一段
     * 用户已经要求放弃的文本。
     */
    @Test
    void deltaArrivingAfterCancellationIsDiscardedAndNotPublished() {
        CompletableFuture<ChatResponseEntity> future = new CompletableFuture<>();
        ExecutionControlSignal control = new ExecutionControlSignal("execution-1");
        RecordingListener listener = new RecordingListener();
        StreamingModelResponseBehaveDecider handler =
                decider(future, control, new AgentEventPublisher(List.of(listener)));
        RecordingTransport transport = new RecordingTransport();

        control.requireCancel();
        handler.onPartialResponse("dropped", transport);

        assertTrue(future.isCompletedExceptionally());
        assertTrue(listener.partialTexts.isEmpty(), "被中断的 delta 不得进入事件流");
        assertEquals(1, transport.cancelCount());
    }

    /**
     * 取消优先于挂起：两者同时到达时，传输层必须按取消关闭，循环才能落到 CANCELLED 而不是 SUSPENDED。
     */
    @Test
    void cancelOutranksSuspendWhenBothAreRequested() {
        CompletableFuture<ChatResponseEntity> future = new CompletableFuture<>();
        ExecutionControlSignal control = new ExecutionControlSignal("execution-1");
        StreamingModelResponseBehaveDecider handler = decider(future, control);
        RecordingTransport transport = new RecordingTransport();

        control.requireSuspend();
        control.requireCancel();
        handler.onPartialResponse("partial", transport);

        ExecutionException thrown = assertThrows(ExecutionException.class, future::get);
        assertEquals(ExecutionInterruptedException.Kind.CANCEL,
                assertInstanceOf(ExecutionInterruptedException.class, thrown.getCause()).kind());
        assertEquals(1, transport.cancelCount());
    }

    /**
     * 工具调用阶段同样要能观察取消：该阶段没有文本 delta，少了这个钩子就只能等整轮流读完。
     */
    @Test
    void toolCallDeltaObservesCancellation() {
        CompletableFuture<ChatResponseEntity> future = new CompletableFuture<>();
        ExecutionControlSignal control = new ExecutionControlSignal("execution-1");
        StreamingModelResponseBehaveDecider handler = decider(future, control);
        RecordingTransport transport = new RecordingTransport();

        control.requireCancel();
        handler.onPartialToolCall(0, "call_1", "get_weather", "{\"city\"", transport);

        assertTrue(future.isCompletedExceptionally());
        ExecutionException thrown = assertThrows(ExecutionException.class, future::get);
        assertEquals(ExecutionInterruptedException.Kind.CANCEL,
                assertInstanceOf(ExecutionInterruptedException.class, thrown.getCause()).kind());
        assertEquals(1, transport.cancelCount());
    }

    /** 未被要求停止时，工具调用 delta 不得触碰传输层，否则正常输出也会被切断。 */
    @Test
    void toolCallDeltaDoesNotTouchTheTransportWithoutARequest() {
        CompletableFuture<ChatResponseEntity> future = new CompletableFuture<>();
        ExecutionControlSignal control = new ExecutionControlSignal("execution-1");
        StreamingModelResponseBehaveDecider handler = decider(future, control);
        RecordingTransport transport = new RecordingTransport();

        handler.onPartialToolCall(0, "call_1", "get_weather", "{\"city\"", transport);

        assertFalse(future.isDone());
        assertEquals(0, transport.cancelCount());
    }

    /** First partial tool call chunk emits a single prepare event carrying metadata and response identity. */
    @Test
    void publishesToolCallPrepareOncePerToolCallWithMetadataAndResponseIdentity() throws Exception {
        RecordingListener listener = new RecordingListener();
        StreamingModelResponseBehaveDecider handler = new StreamingModelResponseBehaveDecider(
                new RuntimeEventPublisher(List.of(listener)),
                new AgentEventPublisher(List.of(listener)),
                StreamingModelResponseBehaveDecider.StreamingResponseContext.builder()
                        .executionId("execution-1")
                        .agentId("chatAgent")
                        .responseId("1234567890123456789")
                        .eventMetaData(Map.of("turnId", "9001", "sessionId", "8001"))
                        .future(new CompletableFuture<>())
                        .build(),
                null);
        RecordingTransport transport = new RecordingTransport();

        handler.onPartialToolCall(0, "call-1", "edit_file", "{\"path\":", transport);
        handler.onPartialToolCall(0, "call-1", "edit_file", "\"a.txt\"}", transport);
        handler.onPartialToolCall(1, "", "read_file", "{}", transport);
        handler.onPartialToolCall(1, "call-invalid", null, "{}", transport);
        handler.onPartialToolCall(1, "call-2", "read_file", "{\"path\":\"b.txt\"}", transport);

        assertEquals(2, listener.prepareEvents.size());
        ToolCallPrepareEvent first = listener.prepareEvents.get(0);
        assertEquals("TOOL_CALL_PREPARE", first.type());
        assertEquals("execution-1", first.executionId());
        assertEquals("chatAgent", first.getAgentId());
        assertEquals("1234567890123456789", first.getResponseId());
        assertEquals(Map.of("turnId", "9001", "sessionId", "8001"), first.eventMetaData());
        assertEquals("call-1", first.getToolCallId());
        assertEquals("edit_file", first.getToolName());
        assertEquals(0, first.getIndex());
        assertEquals("{\"path\":", first.getPartialArguments());
        assertNotNull(first.timestamp());

        assertEquals("call-2", listener.prepareEvents.get(1).getToolCallId());
        assertEquals(1, listener.prepareEvents.get(1).getIndex());

        ObjectMapper mapper = ExecutionJson.newObjectMapper();
        JsonNode json = mapper.readTree(mapper.writeValueAsString(first));
        assertEquals("1234567890123456789", json.path("responseId").asText());
        assertEquals("9001", json.path("metaData").path("turnId").asText());
        assertEquals("call-1", json.path("toolCallId").asText());
        assertEquals("edit_file", json.path("toolName").asText());
        assertTrue(json.hasNonNull("timestamp"));
    }

    /** Once the stream is interrupted, subsequent tool deltas must not publish prepare events. */
    @Test
    void interruptedPartialToolCallDoesNotPublishPrepareEvent() {
        RecordingListener listener = new RecordingListener();
        CompletableFuture<ChatResponseEntity> future = new CompletableFuture<>();
        ExecutionControlSignal control = new ExecutionControlSignal("execution-1");
        StreamingModelResponseBehaveDecider handler = decider(future, control, new AgentEventPublisher(List.of(listener)));
        RecordingTransport transport = new RecordingTransport();

        control.requireCancel();
        handler.onPartialToolCall(0, "call-1", "edit_file", "{}", transport);

        assertTrue(listener.prepareEvents.isEmpty());
        assertEquals(1, transport.cancelCount());
    }

    @Test
    void idleTimeoutCompletesFutureWithReadableMessageAndCancelsTransportAsynchronously() throws Exception {
        CompletableFuture<ChatResponseEntity> future = new CompletableFuture<>();
        StreamingModelResponseBehaveDecider handler = new StreamingModelResponseBehaveDecider(
                new RuntimeEventPublisher(List.of()),
                new AgentEventPublisher(List.of()),
                StreamingModelResponseBehaveDecider.StreamingResponseContext.builder()
                        .executionId("execution-1")
                        .agentId("chatAgent")
                        .responseId("1234567890123456789")
                        .future(future)
                        .build(),
                null,
                Duration.ofMillis(80),
                Duration.ofMillis(20));
        RecordingTransport transport = new RecordingTransport();

        handler.onStreamStart(transport);

        ExecutionException thrown = assertThrows(ExecutionException.class,
                () -> future.get(2, TimeUnit.SECONDS));
        ResponseTimeoutException timeout = assertInstanceOf(ResponseTimeoutException.class, thrown.getCause());
        assertNotNull(timeout.getMessage());
        assertTrue(timeout.getMessage().contains("Streaming response idle timeout"));
        assertTrue(timeout.getMessage().contains("activeWindow=80 ms"));
        assertTrue(timeout.getMessage().contains("executionId=execution-1"));
        assertTrue(timeout.getMessage().contains("responseId=1234567890123456789"));
        assertTrue(transport.awaitCancel(2, TimeUnit.SECONDS));
        assertEquals(1, transport.cancelCount());
    }

    @Test
    void blockingCancelOnOneStreamDoesNotStallOtherConcurrentStreams() throws Exception {
        CountDownLatch cancelEntered = new CountDownLatch(1);
        CountDownLatch releaseCancel = new CountDownLatch(1);
        CompletableFuture<ChatResponseEntity> futureA = new CompletableFuture<>();
        CompletableFuture<ChatResponseEntity> futureB = new CompletableFuture<>();

        StreamingModelResponseBehaveDecider deciderA = new StreamingModelResponseBehaveDecider(
                new RuntimeEventPublisher(List.of()),
                new AgentEventPublisher(List.of()),
                StreamingModelResponseBehaveDecider.StreamingResponseContext.builder()
                        .executionId("exec-a")
                        .agentId("chatAgent")
                        .responseId("resp-a")
                        .future(futureA)
                        .build(),
                null,
                Duration.ofMillis(50),
                Duration.ofMillis(15));
        StreamingModelResponseBehaveDecider deciderB = new StreamingModelResponseBehaveDecider(
                new RuntimeEventPublisher(List.of()),
                new AgentEventPublisher(List.of()),
                StreamingModelResponseBehaveDecider.StreamingResponseContext.builder()
                        .executionId("exec-b")
                        .agentId("chatAgent")
                        .responseId("resp-b")
                        .future(futureB)
                        .build(),
                null,
                Duration.ofMillis(70),
                Duration.ofMillis(15));

        StreamingHandler blockingTransport = new StreamingHandler() {
            @Override
            public void cancel() {
                cancelEntered.countDown();
                try {
                    releaseCancel.await(5, TimeUnit.SECONDS);
                } catch (InterruptedException ignored) {
                    Thread.currentThread().interrupt();
                }
            }

            @Override
            public boolean isCancelled() {
                return cancelEntered.getCount() == 0;
            }
        };
        RecordingTransport fastTransport = new RecordingTransport();

        try {
            deciderA.onStreamStart(blockingTransport);
            deciderB.onStreamStart(fastTransport);

            assertTrue(cancelEntered.await(2, TimeUnit.SECONDS));
            ExecutionException thrownA = assertThrows(ExecutionException.class,
                    () -> futureA.get(2, TimeUnit.SECONDS));
            assertInstanceOf(ResponseTimeoutException.class, thrownA.getCause());

            ExecutionException thrownB = assertThrows(ExecutionException.class,
                    () -> futureB.get(2, TimeUnit.SECONDS));
            assertInstanceOf(ResponseTimeoutException.class, thrownB.getCause());
            assertTrue(fastTransport.awaitCancel(2, TimeUnit.SECONDS));
        } finally {
            releaseCancel.countDown();
        }
    }

    @Test
    void partialActivityKeepsStreamAlivePastActiveWindow() throws Exception {
        CompletableFuture<ChatResponseEntity> future = new CompletableFuture<>();
        StreamingModelResponseBehaveDecider handler = new StreamingModelResponseBehaveDecider(
                new RuntimeEventPublisher(List.of()),
                new AgentEventPublisher(List.of()),
                StreamingModelResponseBehaveDecider.StreamingResponseContext.builder()
                        .executionId("execution-1")
                        .agentId("chatAgent")
                        .responseId("1234567890123456789")
                        .future(future)
                        .build(),
                null,
                Duration.ofMillis(120),
                Duration.ofMillis(20));
        RecordingTransport transport = new RecordingTransport();

        handler.onStreamStart(transport);
        for (int i = 0; i < 4; i++) {
            Thread.sleep(45);
            handler.onPartialResponse("chunk-" + i, transport);
        }
        handler.onFinalResponse(response("completed"));

        assertEquals("completed", future.get(1, TimeUnit.SECONDS).getAiMessageEntity().text());
        assertEquals(0, transport.cancelCount());
    }

    private static ChatResponseEntity response(String text) {
        return ChatResponseEntity.builder()
                .aiMessageEntity(AiMessageEntity.builder().text(text).build())
                .tokenUsage(TokenUsageEntity.empty())
                .build();
    }

    private static StreamingModelResponseBehaveDecider decider(
            CompletableFuture<ChatResponseEntity> future, ExecutionControlSignal control) {
        return decider(future, control, new AgentEventPublisher(List.of()));
    }

    private static StreamingModelResponseBehaveDecider decider(
            CompletableFuture<ChatResponseEntity> future, ExecutionControlSignal control,
            AgentEventPublisher publisher) {
        return new StreamingModelResponseBehaveDecider(
                new RuntimeEventPublisher(List.of()),
                publisher,
                StreamingModelResponseBehaveDecider.StreamingResponseContext.builder()
                        .executionId("execution-1").agentId("chatAgent").responseId("1234567890123456789").future(future).build(),
                control);
    }

    /** 传输层取消句柄的替身：只记录是否被要求关闭、以及被要求了几次。 */
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

    private static final class RecordingListener implements AgentStreamListener, RuntimeListener {
        @Override public int order() { return 0; }
        private final List<String> partialTexts = new ArrayList<>();
        private final List<AgentPartialTextEvent> textEvents = new ArrayList<>();
        private final List<AgentPartialThinkingEvent> thinkingEvents = new ArrayList<>();
        private final List<ToolCallPrepareEvent> prepareEvents = new ArrayList<>();

        @Override
        public void onPartialText(AgentPartialTextEvent event) {
            partialTexts.add(event.content());
            textEvents.add(event);
        }

        @Override
        public void onPartialThinking(AgentPartialThinkingEvent event) {
            thinkingEvents.add(event);
        }

        @Override
        public void onToolCallPrepare(ToolCallPrepareEvent event) {
            prepareEvents.add(event);
        }
    }
}
