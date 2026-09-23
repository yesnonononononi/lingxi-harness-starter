package com.summit.core.conversation.message.content;

import com.summit.core.conversation.message.ContentType;

public class PdfFileContent implements Content{
    @Override
    public ContentType type() {
        return ContentType.PDF;
    }
}
