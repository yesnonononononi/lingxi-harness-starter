package com.summit.runtime.model;

import com.summit.harnesscore.conversation.event.*;
import com.summit.harnesscore.model.StreamingModelResponseHandler;
import dev.langchain4j.model.chat.response.*;
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
    public record StreamingResponseContext(Serializable sessionId,String executionId, String agentId, CompletableFuture<ChatResponse> future) {}
    private final RuntimeEventPublisher runtimeEventPublisher;
    private final StreamingResponseContext streamingResponseContext;

    @Override
    public void onPartialResponse(PartialResponse partialResponse, PartialResponseContext context) {
        this.runtimeEventPublisher.onPartialText(
                AgentPartialTextEvent.builder()
                        .sessionId(streamingResponseContext.sessionId())
                        .content(partialResponse.text())
                        .agentId(streamingResponseContext.agentId())
                        .executionId(streamingResponseContext.executionId())
                        .build()
        );
    }


    @Override
    public void onPartialThinking(PartialThinking partialThinking, PartialThinkingContext context) {
        this.runtimeEventPublisher.onPartialThinking(
                AgentPartialThinkingEvent.builder()
                        .sessionId(streamingResponseContext.sessionId())
                        .agentId(streamingResponseContext.agentId())
                        .executionId(streamingResponseContext.executionId())
                        .content(partialThinking.text())
                        .build()
        );
    }

    @Override
    public void onCompleteResponse(ChatResponse completeResponse) {
        this.streamingResponseContext.future().complete(completeResponse);
    }


    @Override
    public void onError(Throwable error) {
        log.error("streaming model request failed, executionId={}", this.streamingResponseContext.executionId(), error);
        this.streamingResponseContext.future().completeExceptionally(error);
    }
}
