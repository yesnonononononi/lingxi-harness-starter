package com.summit.harnesscore.model;


import com.summit.harnesscore.conversation.api.ChatResponseEntity;

@FunctionalInterface
public interface ModelInvoker {
    ChatResponseEntity invoke(ModelChatCommand chatCommand);
}
