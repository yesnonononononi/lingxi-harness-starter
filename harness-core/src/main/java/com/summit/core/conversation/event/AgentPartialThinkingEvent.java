package com.summit.core.conversation.event;

import lombok.Builder;

import java.time.Instant;
import java.util.Map;
import java.util.UUID;

/**
 * Streaming reasoning / thinking delta of the model response.
 *
 * <p>High-frequency event: one instance per token chunk, hence the immutable {@code record} shape
 * and the lazily defaulted {@link #timestamp}.</p>
 */
@Builder
public record AgentPartialThinkingEvent(
        String agentId,
        String executionId,
        String content,
        UUID responseId,
        Map<String, Object> metaData,
        Instant timestamp
) implements AgentEvent {
    @Override
    public Map<String, Object> eventMetaData() {
        return metaData;
    }


    public AgentPartialThinkingEvent {
        metaData = metaData == null ? Map.of() : Map.copyOf(metaData);
        if (timestamp == null) {
            timestamp = Instant.now();
        }
    }

    @Override
    public String type() {
        return RuntimeEventType.PARTIAL_THINKING.type();
    }
}
