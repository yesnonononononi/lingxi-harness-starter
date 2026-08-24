package com.summit.runtime.model;

import com.summit.harnesscore.model.ChatModelInvoker;
import dev.langchain4j.model.chat.ChatModel;
import dev.langchain4j.model.chat.request.ChatRequest;
import dev.langchain4j.model.chat.response.ChatResponse;

public class DefaultChatModelInvoker implements ChatModelInvoker {
    private final ChatModel model;

    public DefaultChatModelInvoker(ChatModel model) {
        this.model = model;
    }

    @Override
    public ChatResponse invoke(ChatRequest chatRequest) {
       return this.model.chat(chatRequest);
    }
}
