package com.summit.harnesscore.conversation.message;


import com.summit.harnesscore.tool.ToolDefinition;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.NoArgsConstructor;
import lombok.Getter;

import java.io.Serializable;

@Builder
@NoArgsConstructor
@AllArgsConstructor
@Getter
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
