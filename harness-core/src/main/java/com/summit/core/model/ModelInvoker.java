package com.summit.core.model;


import com.summit.core.conversation.api.ChatResponseEntity;

@FunctionalInterface
public interface ModelInvoker {
    ChatResponseEntity invoke(ModelChatCommand chatCommand);
}
