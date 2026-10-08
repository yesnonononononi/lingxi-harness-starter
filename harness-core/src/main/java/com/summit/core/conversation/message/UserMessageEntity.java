package com.summit.core.conversation.message;


import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.summit.core.agent.Image;
import com.summit.core.conversation.message.content.Content;
import com.summit.core.conversation.message.content.ImageContent;
import com.summit.core.conversation.message.content.TextContent;
import lombok.*;

import java.util.ArrayList;
import java.util.List;

@ToString
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Getter
@JsonIgnoreProperties(value = "type", allowGetters = true)
public class UserMessageEntity implements Message{
    private List<Content> content;
    private final MessageType type = MessageType.USER;
    @Override
    public String text() {
        for (Content c : content) {
            if(c instanceof TextContent t){
                return t.getText();
            }
        }
        return "";
    }
    @Override
    public MessageType type() {
        return this.type;
    }
    public static UserMessageEntity from(@NonNull String text,@NonNull Image image){
        return UserMessageEntity.builder()
                .content(new ArrayList<>(List.of(TextContent.from(text), ImageContent.from(image))))
                .build();
    }
    public static UserMessageEntity from(@NonNull Image image){
        return UserMessageEntity.builder()
                .content(new ArrayList<>(List.of(ImageContent.from(image))))
                .build();
    }

    public static  UserMessageEntity from(@NonNull String text){
        return UserMessageEntity.builder()
                .content(new ArrayList<>(List.of(TextContent.from(text))))
                .build();
    }
}
