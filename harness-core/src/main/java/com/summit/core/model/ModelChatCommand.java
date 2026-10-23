package com.summit.core.model;


import com.summit.core.conversation.api.ChatRequestEntity;
import com.summit.core.conf.ModelRetryConfig;
import com.summit.core.conversation.event.RuntimeEventPublisher;
import com.summit.core.model.streaming.StreamingChatResponseHandler;

import com.summit.core.runtime.loop.ExecutionControlSignal;
import lombok.Builder;
import java.util.Map;

@Builder
public record ModelChatCommand(
        String executionId,
        String responseId,
        ChatRequestEntity chatRequest,
        ExecutionControlSignal signal,
        StreamingChatResponseHandler streamingChatResponseHandler,
        boolean streaming,
        boolean thinking,
        RuntimeEventPublisher runtimeEventPublisher,
        ModelRetryConfig retryConfig,
        ModelInvocationUsage invocationUsage,
        Runnable beforeAttempt,
        Map<String, Object> eventMetaData
) {
    public ModelChatCommand {
        eventMetaData = eventMetaData == null ? Map.of() : Map.copyOf(eventMetaData);
    }
    public boolean requireRetry() {
        return retryConfig != null && retryConfig.isEnabled();
    }
}
