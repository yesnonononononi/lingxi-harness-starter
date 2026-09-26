package com.summit.core.conversation.event;

import lombok.Builder;

import java.time.Instant;

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
        Instant timestamp
) implements AgentEvent {

    public AgentPartialThinkingEvent {
        if (timestamp == null) {
            timestamp = Instant.now();
        }
    }

    @Override
    public String type() {
        return RuntimeEventType.PARTIAL_THINKING.type();
    }
}
