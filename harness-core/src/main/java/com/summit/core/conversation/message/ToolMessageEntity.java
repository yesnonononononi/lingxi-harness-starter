package com.summit.core.conversation.message;


import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.summit.core.tool.ToolDefinition;
import lombok.*;

import java.io.Serializable;
@ToString
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Getter
@Setter
@JsonIgnoreProperties(value = "type", allowGetters = true)
public class ToolMessageEntity implements Message{
    private Serializable id;
    private String name;
    private String text;
    private final MessageType type = MessageType.TOOL;
    @Override
    public String text() {
        return this.text;
    }

    @Override
    public MessageType type() {
        return this.type;
    }

    public ToolMessageEntity from(Serializable id, String name, String text){
        return ToolMessageEntity.builder()
                .id(id)
                .name(name)
                .text(text)
                .build();
    }
    public ToolMessageEntity from(ToolDefinition<?> toolDefinition,String text){
        return from( toolDefinition.id(),toolDefinition.name(),text);
    }
}
