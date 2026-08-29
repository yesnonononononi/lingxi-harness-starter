package com.summit.harnesscore.conversation;

import com.summit.harnesscore.agent.AgentRequest;
import com.summit.harnesscore.compact.ContextSummary;
import com.summit.harnesscore.conversation.api.ChatResponseEntity;
import com.summit.harnesscore.conversation.message.Message;
import com.summit.harnesscore.conversation.message.TokenUsageEntity;
import com.summit.harnesscore.tool.ToolExecuteResult;


import java.io.Serializable;
import java.util.List;

public interface ConversationManager {

    void startConversation(AgentRequest agentRequest);

    void addMessage(Serializable sessionId, ChatResponseEntity chatResponse,  List<ToolExecuteResult> toolExecutionResultMessage);
    ConversationEntity endConversation(Serializable sessionId);

    List<Message> messages(Serializable sessionId);

    TokenUsageEntity tokenUsage(Serializable sessionId);

    void squeezeContext(Integer expectedTokens, Integer attemptNum, Serializable sessionId);

    void rebuildContext(ContextSummary contextSummary, Serializable sessionId);
}
