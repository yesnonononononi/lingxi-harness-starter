package com.summit.runtime.model;

import com.summit.core.conversation.api.ChatResponseEntity;
import com.summit.core.conversation.event.AgentPartialTextEvent;
import com.summit.core.conversation.event.RuntimeEventPublisher;
import com.summit.core.conversation.message.AiMessageEntity;
import com.summit.core.conversation.message.TokenUsageEntity;
import com.summit.core.model.streaming.StreamingHandler;
import com.summit.core.runtime.RuntimeListener;
import com.summit.core.runtime.loop.ExecutionControlSignal;
import com.summit.core.runtime.loop.suspension.ExecutionInterruptedException;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 流式回调中的过程中断契约。
 *
 * <p>控制信号只在循环边界被观察，而一次流式模型调用可能持续很久。若信号在调用进行中到达，
 * handler 必须在收到下一个回调时立即结束 future、关闭底层传输句柄，让阻塞在 {@code join()} 上的
 * 调用方拿回控制权，而不是等模型把用户已经要求放弃的内容全部输出完。</p>
 */
class StreamingModelResponseBehaveDeciderTest {

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
                decider(future, control, new RuntimeEventPublisher(List.of(listener)));
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
        handler.onPartialToolCall("{\"city\"", transport);

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

        handler.onPartialToolCall("{\"city\"", transport);

        assertFalse(future.isDone());
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
        return decider(future, control, new RuntimeEventPublisher(List.of()));
    }

    private static StreamingModelResponseBehaveDecider decider(
            CompletableFuture<ChatResponseEntity> future, ExecutionControlSignal control,
            RuntimeEventPublisher publisher) {
        return new StreamingModelResponseBehaveDecider(
                publisher,
                StreamingModelResponseBehaveDecider.StreamingResponseContext.builder()
                        .executionId("execution-1").agentId("chatAgent").responseId(UUID.randomUUID()).future(future).build(),
                control);
    }

    /** 传输层取消句柄的替身：只记录是否被要求关闭、以及被要求了几次。 */
    private static final class RecordingTransport implements StreamingHandler {
        private int cancelCount;

        @Override
        public void cancel() {
            cancelCount++;
        }

        @Override
        public boolean isCancelled() {
            return cancelCount > 0;
        }

        int cancelCount() {
            return cancelCount;
        }
    }

    private static final class RecordingListener implements RuntimeListener {
        private final List<String> partialTexts = new ArrayList<>();

        @Override
        public void onPartialText(AgentPartialTextEvent event) {
            partialTexts.add(event.content());
        }
    }
}
