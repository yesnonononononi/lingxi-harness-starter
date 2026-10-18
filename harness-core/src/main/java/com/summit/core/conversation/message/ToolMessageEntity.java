package com.summit.core.conversation.message;


import com.fasterxml.jackson.annotation.JsonAlias;
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
    @Builder.Default
    private boolean isError = false;
    /**
     * Persisted snapshots wrote this as {@code text} before the rename. The alias keeps stored
     * executions and session messages restorable instead of failing on an unknown property.
     */
    @JsonAlias("text")
    private String toolOutput;
    private final MessageType type = MessageType.TOOL;
    @Override
    public String text() {
        return this.toolOutput;
    }

    @Override
    public MessageType type() {
        return this.type;
    }

    public ToolMessageEntity from(Serializable id, String name, String text){
        return ToolMessageEntity.builder()
                .id(id)
                .name(name)
                .toolOutput(text)
                .build();
    }
    public ToolMessageEntity from(ToolDefinition<?> toolDefinition,String text){
        return from( toolDefinition.id(),toolDefinition.name(),text);
    }
}
