package com.summit.core.compact;

import com.summit.core.conversation.message.Message;

import java.util.List;

public interface ContextCompacter {
     void compact(Integer expectedTokens, Integer attemptNum, List<Message> messages);
}
