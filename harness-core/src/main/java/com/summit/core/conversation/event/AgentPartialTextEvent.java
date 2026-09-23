package com.summit.core.conversation.event;

import lombok.Builder;

import java.time.Instant;

/**
 * Streaming text delta of the model response.
 *
 * <p>High-frequency event: one instance per token chunk, hence the immutable {@code record} shape
 * and the lazily defaulted {@link #timestamp}.</p>
 */
@Builder
public record AgentPartialTextEvent(
        String agentId,
        String executionId,
        String content,
        Instant timestamp
) implements AgentEvent {

    public AgentPartialTextEvent {
        if (timestamp == null) {
            timestamp = Instant.now();
        }
    }

    @Override
    public RuntimeEventType type() {
        return RuntimeEventType.PARTIAL_TEXT;
    }
}
