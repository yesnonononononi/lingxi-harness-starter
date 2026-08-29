package com.summit.harnesscore.model;

import com.summit.harnesscore.conversation.api.ChatResponseEntity;

public interface StreamingChatResponseHandler {
    void onPartialResponse(String text);
    void onPartialThinking(String thinking);
    void onFinalResponse(ChatResponseEntity chatResponseEntity);
    void onError(Throwable err);
}
