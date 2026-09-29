package com.summit.adapter.langchain4j.codec;

import dev.langchain4j.data.image.Image;

public class ImageInfoAdapter {
    public static Image toLangchain4j(com.summit.core.agent.Image image){
        return Image.builder()
                .base64Data(image.getBase64Data())
                .mimeType(image.mimeType())
                .revisedPrompt(image.getRevisedPrompt())
                .url(image.getUrl())
                .build();
    }
}
