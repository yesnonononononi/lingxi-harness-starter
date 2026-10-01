package com.summit.core.conversation.event;

import com.summit.core.conversation.api.ChatResponseEntity;
import lombok.Builder;

import java.time.Instant;
import java.util.Map;

/**
 *  text of the model response.
 */
@Builder
public record AgentCompleteTextEvent(
        String agentId,
        String executionId,
        String content,
        ChatResponseEntity.Meta meta,
        Map<String, Object> metaData,
        Instant timestamp
) implements AgentEvent {

    @Override
    public Map<String, Object> eventMetaData() {
        return metaData;
    }

    public AgentCompleteTextEvent(String agentId, String executionId, String content,
                                  ChatResponseEntity.Meta meta, Instant timestamp) {
        this(agentId, executionId, content, meta, Map.of(), timestamp);
    }

    public AgentCompleteTextEvent {
        metaData = metaData == null ? Map.of() : Map.copyOf(metaData);
        if (timestamp == null) {
            timestamp = Instant.now();
        }
    }

    @Override
    public String type() {
        return RuntimeEventType.COMPLETE_TEXT.type();
    }
}
