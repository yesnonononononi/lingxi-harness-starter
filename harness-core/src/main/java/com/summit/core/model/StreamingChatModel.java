package com.summit.core.model;

import com.summit.core.conversation.api.ChatRequestEntity;

public interface StreamingChatModel {

    void chat(ChatRequestEntity request, StreamingChatResponseHandler handler);
}
