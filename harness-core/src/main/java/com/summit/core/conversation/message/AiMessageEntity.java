package com.summit.core.conversation.message;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.summit.core.conversation.api.ToolCallRequest;
import lombok.*;

import java.util.List;

@Builder
@ToString
@NoArgsConstructor
@AllArgsConstructor
@Getter
@Setter
@JsonIgnoreProperties(value = "type", allowGetters = true)
public class AiMessageEntity implements Message{
    private String text;
    private String thinking;
    private List<ToolCallRequest> toolCalls;
    private final MessageType type = MessageType.AI;

    @Override
    public String text() {
        return this.text;
    }

    @Override
    public MessageType type() {
        return this.type;
    }


}
