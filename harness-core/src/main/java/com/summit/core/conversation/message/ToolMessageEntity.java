package com.summit.core.conversation.message;


import com.summit.core.tool.ToolDefinition;
import lombok.*;

import java.io.Serializable;
@ToString
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Getter
@Setter
public class ToolMessageEntity implements Message{
    private Serializable id;
    private String name;
    private String text;
    @Override
    public String text() {
        return this.text;
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
