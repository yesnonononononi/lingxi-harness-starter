package com.summit.harnesscore.conversation;

import com.summit.harnesscore.agent.AgentRequest;
import com.summit.harnesscore.compact.ContextSummary;
import com.summit.harnesscore.tool.ToolExecuteResult;
import dev.langchain4j.data.message.ChatMessage;
import dev.langchain4j.model.chat.response.ChatResponse;
import dev.langchain4j.model.output.TokenUsage;
import org.jspecify.annotations.Nullable;

import java.io.Serializable;
import java.util.List;

public interface ConversationManager {

    void startConversation(AgentRequest agentRequest);

    void addMessage(Serializable sessionId, ChatResponse chatResponse, @Nullable List<ToolExecuteResult> toolExecutionResultMessage);
    ConversationEntity endConversation(Serializable sessionId);

    List<ChatMessage> messages(Serializable sessionId);

    TokenUsage tokenUsage(Serializable sessionId);

    void squeezeContext(Integer expectedTokens, Integer attemptNum, Serializable sessionId);

    void rebuildContext(ContextSummary contextSummary, Serializable sessionId);
}
