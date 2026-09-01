package com.summit.runtime.model;

import com.summit.core.conversation.api.ChatResponseEntity;
import com.summit.core.conversation.event.*;
import com.summit.core.model.StreamingModelResponseHandler;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.extern.slf4j.Slf4j;

import java.io.Serializable;
import java.util.concurrent.CompletableFuture;

@Slf4j
@Getter
@AllArgsConstructor
public class StreamingModelResponseBehaveDecider implements StreamingModelResponseHandler {
    @Builder
    public record StreamingResponseContext(Serializable sessionId, String executionId, String agentId, CompletableFuture<ChatResponseEntity> future) {}
    private final RuntimeEventPublisher runtimeEventPublisher;
    private final StreamingResponseContext streamingResponseContext;

    @Override
    public void onPartialResponse(String partialResponse) {
        this.runtimeEventPublisher.onPartialText(
                AgentPartialTextEvent.builder()
                        .sessionId(streamingResponseContext.sessionId())
                        .content(partialResponse)
                        .agentId(streamingResponseContext.agentId())
                        .executionId(streamingResponseContext.executionId())
                        .build()
        );
    }


    @Override
    public void onPartialThinking(String partialThinking) {
        this.runtimeEventPublisher.onPartialThinking(
                AgentPartialThinkingEvent.builder()
                        .sessionId(streamingResponseContext.sessionId())
                        .agentId(streamingResponseContext.agentId())
                        .executionId(streamingResponseContext.executionId())
                        .content(partialThinking)
                        .build()
        );
    }

    @Override
    public void onFinalResponse(ChatResponseEntity completeResponse) {
        this.streamingResponseContext.future().complete(completeResponse);
    }


    @Override
    public void onError(Throwable error) {
        log.error("streaming model request failed, executionId={}", this.streamingResponseContext.executionId(), error);
        this.streamingResponseContext.future().completeExceptionally(error);
    }
}
