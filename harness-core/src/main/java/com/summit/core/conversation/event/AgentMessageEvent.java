package com.summit.core.conversation.event;

import com.summit.core.conversation.api.ChatResponseEntity;
import lombok.EqualsAndHashCode;
import lombok.Getter;
import lombok.ToString;

import java.util.Map;

/**
 * The final assistant message of one model round — the one object a renderer draws in place of the
 * deltas it received while streaming.
 */
@Getter
@EqualsAndHashCode(callSuper = true)
@ToString(callSuper = true)
public class AgentMessageEvent extends AbstractEvent implements AgentEvent {

    private final ChatResponseEntity chatResponseEntity;
    private final String responseId;

    public AgentMessageEvent(ChatResponseEntity chatResponseEntity, String executionId, String responseId,
                             Map<String, Object> metaData) {
        super(executionId, metaData);
        this.chatResponseEntity = chatResponseEntity;
        this.responseId = responseId;
    }

    @Override
    public String type() {
        return RuntimeEventType.AI_MESSAGE.type();
    }
}
