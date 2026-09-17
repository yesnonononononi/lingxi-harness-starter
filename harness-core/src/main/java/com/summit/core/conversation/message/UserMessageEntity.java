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
    /** Framework-injected instruction for the model; not a message authored by the user. */
    private boolean internal;
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

    public static UserMessageEntity internal(String text) {
        return UserMessageEntity.builder()
                .text(text)
                .internal(true)
                .build();
    }
}
