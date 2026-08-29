package com.summit.harnesscore.conversation.message;


import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.NoArgsConstructor;
import lombok.Getter;

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
