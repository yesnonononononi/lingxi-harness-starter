package com.summit.core.conversation.message;

import com.fasterxml.jackson.annotation.JsonSubTypes;
import com.fasterxml.jackson.annotation.JsonTypeInfo;

@JsonTypeInfo(use = JsonTypeInfo.Id.NAME, include = JsonTypeInfo.As.EXISTING_PROPERTY, property = "type")
@JsonSubTypes({
        @JsonSubTypes.Type(value = SystemMessageEntity.class, name = "SYSTEM"),
        @JsonSubTypes.Type(value = UserMessageEntity.class, name = "USER"),
        @JsonSubTypes.Type(value = AiMessageEntity.class, name = "AI"),
        @JsonSubTypes.Type(value = ToolMessageEntity.class, name = "TOOL")
})
public interface Message {
    String text();
    MessageType type();
}
