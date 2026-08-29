package com.summit.harnesscore.model;

import com.summit.harnesscore.conversation.api.ChatRequestEntity;
import com.summit.harnesscore.conversation.api.ChatResponseEntity;

public interface ChatModel {
    ChatResponseEntity chat(ChatRequestEntity request);
}
