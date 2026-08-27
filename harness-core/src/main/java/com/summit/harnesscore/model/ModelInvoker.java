package com.summit.harnesscore.model;

import dev.langchain4j.model.chat.response.ChatResponse;
@FunctionalInterface
public interface ModelInvoker {
    ChatResponse invoke(ModelChatCommand chatCommand);
}
