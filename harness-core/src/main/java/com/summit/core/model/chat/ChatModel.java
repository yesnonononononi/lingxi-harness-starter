package com.summit.core.model.chat;

import com.summit.core.conversation.api.ChatRequestEntity;
import com.summit.core.conversation.api.ChatResponseEntity;

public interface ChatModel {
    ChatResponseEntity chat(ChatRequestEntity request);
}
