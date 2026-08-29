package com.summit.harnesscore.compact;

import com.summit.harnesscore.conversation.message.Message;

import java.util.List;

public interface ContextCompacter {
     void compact(Integer expectedTokens, Integer attemptNum, List<Message> messages);
}
