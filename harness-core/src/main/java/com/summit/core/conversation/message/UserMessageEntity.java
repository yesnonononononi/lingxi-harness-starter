package com.summit.core.conversation.message;


import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import lombok.*;

@ToString
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Getter
@JsonIgnoreProperties(value = "type", allowGetters = true)
public class UserMessageEntity implements Message{
    private String text;
    private final MessageType type = MessageType.USER;
    @Override
    public String text() {
        return this.text;
    }
    @Override
    public MessageType type() {
        return this.type;
    }
    public static  UserMessageEntity from(String text){
        return UserMessageEntity.builder()
                .text(text)
                .build();
    }
}
