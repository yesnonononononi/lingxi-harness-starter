package com.summit.core.conversation.message.content;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.summit.core.agent.Image;
import com.summit.core.conversation.message.ContentType;
import lombok.Getter;

@Getter
public class ImageContent implements Content{
    private Image image;
    @JsonCreator
    public ImageContent(@JsonProperty("image") Image image) { this.image = image; }
    public static ImageContent from(Image image){
        return new ImageContent(image);
    }
    @Override
    public ContentType type() {
        return ContentType.IMAGE;
    }
}
