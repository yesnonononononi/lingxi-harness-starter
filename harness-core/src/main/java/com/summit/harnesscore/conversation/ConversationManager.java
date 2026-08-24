package com.summit.harnesscore.conversation;

import com.summit.harnesscore.agent.AgentRequest;
import com.summit.harnesscore.compact.ContextSummary;
import com.summit.harnesscore.tool.ToolExecuteResult;
import dev.langchain4j.data.message.ChatMessage;
import dev.langchain4j.model.chat.response.ChatResponse;
import dev.langchain4j.model.output.TokenUsage;
import org.jspecify.annotations.Nullable;

import java.util.List;

public interface ConversationManager {
    void startConversation(AgentRequest agentRequest);
    void addMessage(ChatResponse chatResponse, @Nullable List<ToolExecuteResult> toolExecutionResultMessage);
    void endConversation();
    List<ChatMessage> messages();
    TokenUsage tokenUsage();
    void squeezeContext(Integer expectedTokens, Integer attemptNum);
    void rebuildContext(ContextSummary contextSummary);
}
