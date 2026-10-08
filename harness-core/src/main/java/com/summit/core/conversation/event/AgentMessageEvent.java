package com.summit.core.conversation.event;

import lombok.Data;

import java.time.Instant;
import java.util.Map;
import java.util.UUID;

@Data
public class AgentMessageEvent implements AgentEvent{
    private final String text;
    private final String thinking;
    private final String executionId;
    private final UUID responseId;
    private final Map<String,Object> metaData;
    private final Instant timestamp = Instant.now();


    public AgentMessageEvent(String text, String thinking, String executionId, UUID responseId,Map<String,Object> metaData) {
        this.text = text;
        this.thinking = thinking;
        this.executionId = executionId;
        this.responseId = responseId;
        this.metaData = metaData == null ? Map.of() : Map.copyOf(metaData);
    }


    @Override
    public Map<String, Object> eventMetaData() {
        return metaData;
    }

    @Override
    public String executionId() {
        return executionId;
    }

    @Override
    public Instant timestamp() {
        return timestamp;
    }

    @Override
    public String type() {
        return RuntimeEventType.AI_MESSAGE.type();
    }
}
