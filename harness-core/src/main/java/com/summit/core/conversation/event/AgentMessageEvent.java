package com.summit.core.conversation.event;

import com.summit.core.conversation.api.ChatResponseEntity;
import lombok.Data;

import java.time.Instant;
import java.util.Map;

@Data
public class AgentMessageEvent implements AgentEvent{
    private final ChatResponseEntity chatResponseEntity;
    private final String executionId;
    private final String responseId;
    private final Map<String,Object> metaData;
    private final Instant timestamp = Instant.now();


    public AgentMessageEvent(ChatResponseEntity chatResponseEntity, String executionId, String responseId,Map<String,Object> metaData) {
        this.chatResponseEntity = chatResponseEntity;
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
