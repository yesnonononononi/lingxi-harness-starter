package com.summit.core.conversation.message;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import lombok.*;

@ToString
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Getter
@JsonIgnoreProperties(value = "type", allowGetters = true)
public class SystemMessageEntity implements Message{
    private String text;
    private final MessageType type = MessageType.SYSTEM;
    @Override
    public String text() {
        return this.text;
    }

    @Override
    public MessageType type() {
        return this.type;
    }

    public SystemMessageEntity from(String text){
        return SystemMessageEntity.builder().text(text).build();
    }

}
