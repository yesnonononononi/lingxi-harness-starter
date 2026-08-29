package com.summit.harnesscore.conversation.message;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.NoArgsConstructor;
import lombok.Getter;

@Builder
@NoArgsConstructor
@AllArgsConstructor
@Getter
public class SystemMessageEntity implements Message{
    private String text;
    @Override
    public String text() {
        return this.text;
    }

    public SystemMessageEntity from(String text){
        return SystemMessageEntity.builder().text(text).build();
    }

}
