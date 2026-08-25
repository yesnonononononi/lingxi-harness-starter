package com.summit.harnesscore.compact;

import dev.langchain4j.data.message.ChatMessage;

import java.util.List;

public interface ContextCompacter {
     void compact(Integer expectedTokens, Integer attemptNum, List<ChatMessage> messages);
}
