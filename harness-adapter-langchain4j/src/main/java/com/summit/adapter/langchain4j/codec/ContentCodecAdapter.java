package com.summit.adapter.langchain4j.codec;

import com.summit.core.agent.Image;
import com.summit.core.conversation.message.content.Content;
import com.summit.core.conversation.message.content.ImageContent;
import com.summit.core.conversation.message.content.TextContent;

import java.net.URI;
import java.util.List;
import java.util.Objects;

public class ContentCodecAdapter {

    public static dev.langchain4j.data.message.Content toFramework(Content content) {
        switch (content){
            case TextContent textContent ->{
                return dev.langchain4j.data.message.TextContent.from(textContent.getText());
            }
            case ImageContent imageContent ->{
                Image image = imageContent.getImage();
                Objects.requireNonNull(image);
                return resolveImageInfo(image);
            }
            default -> throw new IllegalStateException("Unexpected Content type: " + content);
        }

    }


    public static List<dev.langchain4j.data.message.Content> toFramework(List<Content> t) {
        return t.stream().map(ContentCodecAdapter::toFramework).toList();
    }

    public static dev.langchain4j.data.message.ImageContent resolveImageInfo(Image image){
        URI url = image.getUrl();
        String base64Data = image.getBase64Data();

        if(url != null) return dev.langchain4j.data.message.ImageContent.from(url, dev.langchain4j.data.message.ImageContent.DetailLevel.valueOf(image.detailLevel().name()));
        if(base64Data != null) return dev.langchain4j.data.message.ImageContent.from(base64Data, image.mimeType());
        throw new IllegalArgumentException("Image must have either url or base64Data");
    }
}
