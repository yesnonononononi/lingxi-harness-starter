package com.summit.core.conversation.message;


import lombok.*;

@ToString
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Getter
public class UserMessageEntity implements Message{
    private String text;
    @Override
    public String text() {
        return this.text;
    }
    public static  UserMessageEntity from(String text){
        return UserMessageEntity.builder()
                .text(text)
                .build();
    }
}
