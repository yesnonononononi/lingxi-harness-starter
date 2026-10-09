package com.summit.core.conversation.event;

import lombok.Builder;

import java.io.Serializable;
import java.time.Instant;
import java.util.Map;

/**
 * Streaming text delta of the model response.
 *
 * <p>High-frequency event: one instance per token chunk, hence the immutable {@code record} shape
 * and the lazily defaulted {@link #timestamp}.</p>
 *
 * @param offset zero-based start position in UTF-16 code units within this response's text stream
 */
@Builder
public record AgentPartialTextEvent(
        String agentId,
        String executionId,
        String responseId,
        String content,
        int offset,
        Map<String,Object> metaData,
        Instant timestamp
) implements AgentEvent {
    @Override
    public Map<String, Object> eventMetaData() {
        return metaData;
    }



    public AgentPartialTextEvent {
        metaData = metaData == null ? Map.of() : Map.copyOf(metaData);
        if (timestamp == null) {
            timestamp = Instant.now();
        }
    }

    @Override
    public String type() {
        return RuntimeEventType.PARTIAL_TEXT.type();
    }
}
