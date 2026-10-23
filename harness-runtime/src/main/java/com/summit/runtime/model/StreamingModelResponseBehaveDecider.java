package com.summit.runtime.model;

import com.summit.core.conversation.api.ChatResponseEntity;
import com.summit.core.conversation.event.AgentCompleteTextEvent;
import com.summit.core.conversation.event.AgentPartialTextEvent;
import com.summit.core.conversation.event.AgentPartialThinkingEvent;
import com.summit.core.conversation.event.RuntimeEventPublisher;
import com.summit.core.model.streaming.StreamingHandler;
import com.summit.core.model.streaming.StreamingModelResponseHandler;
import com.summit.core.runtime.loop.ExecutionControlSignal;
import com.summit.core.runtime.loop.suspension.ExecutionInterruptedException;
import lombok.AccessLevel;
import lombok.Builder;
import lombok.Getter;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

@Slf4j
@Getter
@RequiredArgsConstructor
public class StreamingModelResponseBehaveDecider implements StreamingModelResponseHandler {
    private final AtomicBoolean reject = new AtomicBoolean(false);
    @Getter(AccessLevel.NONE)
    private final AtomicReference<StreamingHandler> transport = new AtomicReference<>();

    private int responseOffset;
    private int thinkingOffset;


    @Builder
    public record StreamingResponseContext(
            String executionId, String agentId,
            String responseId,
            CompletableFuture<ChatResponseEntity> future,
            Map<String, Object> eventMetaData
    ) {
        public StreamingResponseContext {
            eventMetaData = eventMetaData == null ? Map.of() : Map.copyOf(eventMetaData);
        }

    }

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
    public void onPartialResponse(String partialResponse, StreamingHandler streamingHandler) {
        if (rejectDelta(streamingHandler)) return;

        int offset = responseOffset;
        responseOffset += partialResponse.length();

        this.runtimeEventPublisher.onPartialText(
                AgentPartialTextEvent.builder()
                        .content(partialResponse)
                        .offset(offset)
                        .responseId(streamingResponseContext.responseId())
                        .agentId(streamingResponseContext.agentId())
                        .executionId(streamingResponseContext.executionId())
                        .metaData(streamingResponseContext.eventMetaData())
                        .build()
        );
    }


    @Override
    public void onPartialThinking(String partialThinking, StreamingHandler streamingHandler) {
        if (rejectDelta(streamingHandler)) return;
        int offset = thinkingOffset;
        thinkingOffset += partialThinking.length();

        this.runtimeEventPublisher.onPartialThinking(
                AgentPartialThinkingEvent.builder()
                        .responseId(streamingResponseContext.responseId())
                        .agentId(streamingResponseContext.agentId())
                        .executionId(streamingResponseContext.executionId())
                        .metaData(streamingResponseContext.eventMetaData())
                        .content(partialThinking)
                        .offset(offset)
                        .build()
        );
    }

    /**
     * A streamed tool call's arguments carry no text, but they are still part of the same stream: a
     * control request arriving while they are being emitted has to cut the stream here too,
     * otherwise the loop would only notice once the whole call had been read — which is exactly the
     * wait this handler exists to avoid. Nothing is published: the assembled call arrives with the
     * round's final response.
     */
    @Override
    public void onPartialToolCall(String partialArguments, StreamingHandler streamingHandler) {
        rejectDelta(streamingHandler);
    }

    private boolean rejectDelta(StreamingHandler streamingHandler) {
        transport.set(streamingHandler);
        if (!interruptIfRequested()) return false;
        cancelTransport();
        return true;
    }

    /**
     * Aborts the pending stream when the execution has been asked to stop.
     *
     * <p>The attempt must reject callbacks before its future wakes the invoker; otherwise a retry
     * can start while the previous attempt still publishes events.</p>
     *
     * @return {@code true} when the stream was aborted and the current delta must be dropped
     */
    private boolean interruptIfRequested() {
        if (reject.get()) return true;
        if (control == null) return false;

        ExecutionInterruptedException.Kind kind = control.isCancelRequired()
                ? ExecutionInterruptedException.Kind.CANCEL
                : control.isSuspendRequired() ? ExecutionInterruptedException.Kind.SUSPEND : null;

        if (kind == null) return reject.get();

        if (reject.compareAndSet(false, true)) {
            try {
                cancelTransport();
            } finally {
                streamingResponseContext.future().completeExceptionally(
                        new ExecutionInterruptedException(kind,
                                "execution interrupted mid-stream: " + kind.name().toLowerCase()));
            }
        }

        return true;
    }

    @Override
    public void onFinalResponse(ChatResponseEntity completeResponse) {
        if (interruptIfRequested() || !reject.compareAndSet(false, true)) return;

        try {
            completeResponse.setResponseId(streamingResponseContext.responseId());
            this.runtimeEventPublisher.onCompleteText(
                    AgentCompleteTextEvent.builder()
                            .agentId(streamingResponseContext.agentId())
                            .responseId(streamingResponseContext.responseId())
                            .executionId(streamingResponseContext.executionId())
                            .metaData(streamingResponseContext.eventMetaData())
                            .content(completeResponse.getAiMessageEntity().getText())
                            .meta(completeResponse.getMeta())
                            .build()
            );

            streamingResponseContext.future().complete(completeResponse);
        } catch (RuntimeException | Error error) {
            try {
                cancelTransport();
            } finally {
                streamingResponseContext.future().completeExceptionally(error);
            }
            throw error;
        }
    }


    @Override
    public void onError(Throwable error) {
        if (!reject.compareAndSet(false, true)) return;
        try {
            cancelTransport();
        } finally {
            log.error("error has happened at the process: executionId={}", streamingResponseContext.executionId(), error);
            streamingResponseContext.future().completeExceptionally(error);
        }
    }

    /** Transport cleanup must not replace the attempt's original failure. */
    private void cancelTransport() {
        StreamingHandler handle = transport.getAndSet(null);
        if (handle == null) return;
        try {
            handle.cancel();
        } catch (RuntimeException error) {
            log.warn("failed to cancel the model response: executionId={}", streamingResponseContext.executionId(), error);
        }
    }
}
