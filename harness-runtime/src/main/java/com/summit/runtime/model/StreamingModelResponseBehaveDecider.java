package com.summit.runtime.model;

import com.summit.core.conversation.api.ChatResponseEntity;
import com.summit.core.conversation.event.*;
import com.summit.core.model.streaming.StreamingModelResponseHandler;
import com.summit.core.runtime.loop.ExecutionControlSignal;
import com.summit.core.runtime.loop.suspension.ExecutionInterruptedException;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.extern.slf4j.Slf4j;

import java.util.concurrent.CompletableFuture;

@Slf4j
@Getter
@AllArgsConstructor
public class StreamingModelResponseBehaveDecider implements StreamingModelResponseHandler {
    @Builder
    public record StreamingResponseContext(String executionId, String agentId, CompletableFuture<ChatResponseEntity> future) {}
    private final RuntimeEventPublisher runtimeEventPublisher;
    private final StreamingResponseContext streamingResponseContext;

    /**
     * Cooperative control signal of the execution this stream belongs to, or {@code null} when the
     * runtime has no signal for it. Read on every delta so a suspend or cancel request issued while
     * the model is still producing output takes effect immediately instead of waiting for the
     * stream to finish.
     */
    private final ExecutionControlSignal control;

    @Override
    public void onPartialResponse(String partialResponse) {
        if (interruptIfRequested()) return;
        this.runtimeEventPublisher.onPartialText(
                AgentPartialTextEvent.builder()
                        .content(partialResponse)
                        .agentId(streamingResponseContext.agentId())
                        .executionId(streamingResponseContext.executionId())
                        .build()
        );
    }


    @Override
    public void onPartialThinking(String partialThinking) {
        if (interruptIfRequested()) return;
        this.runtimeEventPublisher.onPartialThinking(
                AgentPartialThinkingEvent.builder()
                        .agentId(streamingResponseContext.agentId())
                        .executionId(streamingResponseContext.executionId())
                        .content(partialThinking)
                        .build()
        );
    }

    /**
     * Aborts the pending stream when the execution has been asked to stop.
     *
     * <p>Completing the future exceptionally unblocks the {@code join()} held by the streaming
     * invoker, which is the only way to take control back from a model call in progress. The
     * underlying transport may keep draining in the background; the loop stops consuming it, which
     * is what matters for a cooperative suspend.</p>
     *
     * @return {@code true} when the stream was aborted and the current delta must be dropped
     */
    private boolean interruptIfRequested() {
        if (control == null) return false;
        ExecutionInterruptedException.Kind kind = control.isCancelRequired()
                ? ExecutionInterruptedException.Kind.CANCEL
                : control.isSuspendRequired() ? ExecutionInterruptedException.Kind.SUSPEND : null;
        if (kind == null) return false;
        if (!streamingResponseContext.future().isDone()) {
            streamingResponseContext.future().completeExceptionally(
                    new ExecutionInterruptedException(kind,
                            "execution interrupted mid-stream: " + kind.name().toLowerCase()));
        }
        return true;
    }

    @Override
    public void onFinalResponse(ChatResponseEntity completeResponse) {
        if (!streamingResponseContext.future().complete(completeResponse)) {
            log.debug("discarding final response of an interrupted execution, executionId={}",
                    streamingResponseContext.executionId());
        }
    }


    @Override
    public void onError(Throwable error) {
        if (streamingResponseContext.future().isDone()) return;
        log.error("streaming model request failed, executionId={}", this.streamingResponseContext.executionId(), error);
        this.streamingResponseContext.future().completeExceptionally(error);
    }
}
