package com.summit.core.conversation.message.content;

import com.summit.core.conversation.message.ContentType;

public class VideoContent implements Content{
    @Override
    public ContentType type() {
        return ContentType.VIDEO;
    }
}
