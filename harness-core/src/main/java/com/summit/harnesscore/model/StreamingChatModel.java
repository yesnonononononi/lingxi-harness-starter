package com.summit.harnesscore.model;

import com.summit.harnesscore.conversation.api.ChatRequestEntity;

public interface StreamingChatModel {

    void chat(ChatRequestEntity request, StreamingChatResponseHandler handler);
}
