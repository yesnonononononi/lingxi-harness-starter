package com.summit.core.conversation;

import com.summit.core.agent.AgentRequest;
import com.summit.core.compact.ContextSummary;
import com.summit.core.conversation.api.ChatResponseEntity;
import com.summit.core.conversation.message.Message;
import com.summit.core.conversation.message.TokenUsageEntity;
import com.summit.core.runtime.Workspace;
import com.summit.core.workspace.WorkspaceRef;
import com.summit.core.tool.LoopBoundary;
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

    /** Returns the persistence-friendly workspace binding of a session when available. */
    default WorkspaceRef workspaceRef(Serializable sessionId) {
        return null;
    }

    TokenUsageEntity tokenUsage(Serializable sessionId);

    void rebuildContext(ContextSummary contextSummary, Serializable sessionId);

    /**
     * Refreshes the leading system message of an existing session so the model sees the
     * given loop boundary. Used when a PLANING execution has just produced an approved
     * plan and keeps running in the same loop to implement it under the EXECUTE boundary.
     * Default no-op keeps existing implementations intact.
     *
     * @param sessionId          the session whose system message should be refreshed
     * @param boundary           the boundary the model should now operate under
     * @param customSystemPrompt the caller-supplied custom prompt of the current request
     *                           ({@code null} when absent)
     */
    default void refreshBoundary(Serializable sessionId, LoopBoundary boundary, String customSystemPrompt) {
    }

    /**
     * Appends a plain user message to an existing session (used by the runtime to inject the
     * user's plan feedback / the approved plan before the next model round). Default no-op keeps
     * existing implementations intact.
     */
    default void appendUserMessage(Serializable sessionId, String text) {
    }
}
