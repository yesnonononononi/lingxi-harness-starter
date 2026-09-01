package com.summit.core.model;

import com.summit.core.conversation.api.ChatResponseEntity;

public interface StreamingChatResponseHandler {
    void onPartialResponse(String text);
    void onPartialThinking(String thinking);
    void onFinalResponse(ChatResponseEntity chatResponseEntity);
    void onError(Throwable err);
}
