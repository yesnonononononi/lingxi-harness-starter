package com.summit.core.audit;

import com.summit.core.conversation.message.Message;

import java.util.List;
@FunctionalInterface
public interface AgentAuditor <T>{
    T analysis(List<Message> messageList);
}
