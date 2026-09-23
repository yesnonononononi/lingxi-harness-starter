package com.summit.runtime.model;

import com.summit.core.conversation.api.ChatResponseEntity;
import com.summit.core.conversation.event.RuntimeEventPublisher;
import com.summit.core.conversation.message.AiMessageEntity;
import com.summit.core.conversation.message.TokenUsageEntity;
import com.summit.core.runtime.loop.ExecutionControlSignal;
import com.summit.core.runtime.loop.suspension.ExecutionInterruptedException;
import org.junit.jupiter.api.Test;

import java.util.List;
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
 * handler 必须在收到下一个 delta 时立即结束 future，让阻塞在 {@code join()} 上的调用方拿回控制权，
 * 而不是等模型把用户已经要求放弃的内容全部输出完。</p>
 */
class StreamingModelResponseBehaveDeciderTest {

    private static ChatResponseEntity response(String text) {
        return ChatResponseEntity.builder()
                .aiMessageEntity(AiMessageEntity.builder().text(text).build())
                .tokenUsage(TokenUsageEntity.empty())
                .build();
    }

    private static StreamingModelResponseBehaveDecider decider(
            CompletableFuture<ChatResponseEntity> future, ExecutionControlSignal control) {
        return new StreamingModelResponseBehaveDecider(
                new RuntimeEventPublisher(List.of()),
                StreamingModelResponseBehaveDecider.StreamingResponseContext.builder()
                        .executionId("execution-1").agentId("chatAgent").future(future).build(),
                control);
    }

    @Test
    void suspendRequestDuringStreamAbortsThePendingCall() {
        CompletableFuture<ChatResponseEntity> future = new CompletableFuture<>();
        ExecutionControlSignal control = new ExecutionControlSignal("execution-1");
        StreamingModelResponseBehaveDecider handler = decider(future, control);

        handler.onPartialResponse("partial");

        control.requireSuspend();
        handler.onPartialThinking("more thinking");

        assertTrue(future.isCompletedExceptionally());
        ExecutionException thrown = assertThrows(ExecutionException.class, future::get);
        ExecutionInterruptedException interrupt =
                assertInstanceOf(ExecutionInterruptedException.class, thrown.getCause());
        assertEquals(ExecutionInterruptedException.Kind.SUSPEND, interrupt.kind());
    }

    @Test
    void cancelRequestDuringStreamAbortsAsCancellation() {
        CompletableFuture<ChatResponseEntity> future = new CompletableFuture<>();
        ExecutionControlSignal control = new ExecutionControlSignal("execution-1");
        StreamingModelResponseBehaveDecider handler = decider(future, control);

        control.requireCancel();
        handler.onPartialResponse("partial");

        ExecutionException thrown = assertThrows(ExecutionException.class, future::get);
        ExecutionInterruptedException interrupt =
                assertInstanceOf(ExecutionInterruptedException.class, thrown.getCause());
        assertEquals(ExecutionInterruptedException.Kind.CANCEL, interrupt.kind());
    }

    @Test
    void finalResponseIsDiscardedOnceTheStreamHasBeenInterrupted() {
        CompletableFuture<ChatResponseEntity> future = new CompletableFuture<>();
        ExecutionControlSignal control = new ExecutionControlSignal("execution-1");
        StreamingModelResponseBehaveDecider handler = decider(future, control);

        control.requireSuspend();
        handler.onPartialResponse("partial");
        // 传输层仍在后台排空，晚到的完整响应不得覆盖中断结果。
        handler.onFinalResponse(response("late complete answer"));

        assertTrue(future.isCompletedExceptionally());
    }

    @Test
    void streamCompletesNormallyWithoutAControlRequest() throws Exception {
        CompletableFuture<ChatResponseEntity> future = new CompletableFuture<>();
        ExecutionControlSignal control = new ExecutionControlSignal("execution-1");
        StreamingModelResponseBehaveDecider handler = decider(future, control);

        handler.onPartialResponse("hello");
        handler.onFinalResponse(response("hello world"));

        assertEquals("hello world", future.get().getAiMessageEntity().text());
        assertFalse(future.isCompletedExceptionally());
    }

    @Test
    void handlerWithoutAControlSignalNeverInterrupts() throws Exception {
        CompletableFuture<ChatResponseEntity> future = new CompletableFuture<>();
        StreamingModelResponseBehaveDecider handler = decider(future, null);

        handler.onPartialResponse("hello");
        handler.onFinalResponse(response("done"));

        assertEquals("done", future.get().getAiMessageEntity().text());
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
}
