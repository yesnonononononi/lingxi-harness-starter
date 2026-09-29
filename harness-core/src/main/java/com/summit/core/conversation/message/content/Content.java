package com.summit.core.conversation.message.content;

import com.fasterxml.jackson.annotation.JsonSubTypes;
import com.fasterxml.jackson.annotation.JsonTypeInfo;
import com.summit.core.conversation.message.ContentType;

@JsonTypeInfo(use = JsonTypeInfo.Id.NAME, include = JsonTypeInfo.As.PROPERTY, property = "contentType")
@JsonSubTypes({
        @JsonSubTypes.Type(value = TextContent.class, name = "TEXT"),
        @JsonSubTypes.Type(value = ImageContent.class, name = "IMAGE"),
        @JsonSubTypes.Type(value = AudioContent.class, name = "AUDIO"),
        @JsonSubTypes.Type(value = VideoContent.class, name = "VIDEO"),
        @JsonSubTypes.Type(value = PdfFileContent.class, name = "PDF")
})
public interface Content {
    ContentType type();
}
