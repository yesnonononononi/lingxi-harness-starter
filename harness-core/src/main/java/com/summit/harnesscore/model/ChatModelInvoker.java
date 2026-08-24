package com.summit.harnesscore.model;

import dev.langchain4j.model.chat.request.ChatRequest;
import dev.langchain4j.model.chat.response.ChatResponse;

@FunctionalInterface
public interface ChatModelInvoker {
    ChatResponse invoke(ChatRequest chatRequest);
}
