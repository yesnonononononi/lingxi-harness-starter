package com.summit.core.conversation.message.content;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.summit.core.conversation.message.ContentType;
import lombok.Getter;

public class TextContent implements Content{
    @Getter
    private final String text;

    @JsonCreator
    public TextContent(@JsonProperty("text") String text) {
        this.text = text;
    }

    public static TextContent from(String text) {
        return new TextContent(text);
    }

    @Override
    public ContentType type() {
        return ContentType.TEXT;
    }
}
