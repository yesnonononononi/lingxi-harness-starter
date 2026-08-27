package com.summit.harnesscore.model;

import dev.langchain4j.model.chat.request.ChatRequest;
import dev.langchain4j.model.chat.response.StreamingChatResponseHandler;
import lombok.Builder;

@Builder
public record ModelChatCommand (
        ChatRequest chatRequest,
        StreamingChatResponseHandler streamingChatResponseHandler,
        boolean streaming,
        boolean thinking
){

}
