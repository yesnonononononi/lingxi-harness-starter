package com.summit.core.conversation.message;

import lombok.*;

@ToString
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
