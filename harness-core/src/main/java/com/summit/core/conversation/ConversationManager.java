package com.summit.core.conversation;

import com.summit.core.agent.AgentRequest;
import com.summit.core.compact.ContextSummary;
import com.summit.core.conversation.api.ChatResponseEntity;
import com.summit.core.conversation.message.Message;
import com.summit.core.conversation.message.TokenUsageEntity;
import com.summit.core.runtime.Workspace;
import com.summit.core.tool.ToolExecuteResult;


import java.io.Serializable;
import java.util.List;

public interface ConversationManager {

    void startConversation(AgentRequest agentRequest);

    void addMessage(Serializable sessionId, ChatResponseEntity chatResponse,  List<ToolExecuteResult> toolExecutionResultMessage);
    ConversationEntity endConversation(Serializable sessionId);

    List<Message> messages(Serializable sessionId);

    /**
     * Returns the workspace bound to the session — the instance supplied by
     * the {@code AgentRequest} that started it. There is no global fallback;
     * consumers (e.g. patch application) must use this per-session workspace.
     *
     * @return the session workspace, or {@code null} when the session is unknown
     */
    Workspace workspace(Serializable sessionId);

    TokenUsageEntity tokenUsage(Serializable sessionId);

    void squeezeContext(Integer expectedTokens, Integer attemptNum, Serializable sessionId);

    void rebuildContext(ContextSummary contextSummary, Serializable sessionId);
}
