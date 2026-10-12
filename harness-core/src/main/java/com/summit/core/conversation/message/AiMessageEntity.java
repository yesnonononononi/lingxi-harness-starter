package com.summit.core.conversation.message;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.summit.core.conversation.api.ToolCallRequest;
import lombok.*;

import java.util.List;
import java.util.Map;

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
    /** Provider protocol data required to replay the assistant message, including thinking signatures. */
    @Builder.Default
    private Map<String, Object> attributes = Map.of();
    private final MessageType type = MessageType.AI;

    public AiMessageEntity(String text, String thinking, List<ToolCallRequest> toolCalls) {
        this(text, thinking, toolCalls, Map.of());
    }


    @Override
    public String text() {
        return this.text;
    }

    @Override
    public MessageType type() {
        return this.type;
    }


}
