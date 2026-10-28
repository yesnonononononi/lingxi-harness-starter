package com.summit.core.model;


import com.summit.core.conversation.api.ChatResponseEntity;

import java.util.function.Supplier;

@FunctionalInterface
public interface ModelInvoker {
    /** Reports collected usage through each command's invocationUsage before response delivery. */
    ChatResponseEntity invoke(Supplier<ModelChatCommand> chatCommand) throws Exception;
}
