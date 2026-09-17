package com.summit.core.conversation;

import com.summit.core.agent.AgentRequest;
import com.summit.core.compact.ContextSummary;
import com.summit.core.conversation.api.ChatResponseEntity;
import com.summit.core.conversation.message.Message;
import com.summit.core.conversation.message.TokenUsageEntity;
import com.summit.core.runtime.workspace.Workspace;
import com.summit.core.tool.LoopBoundary;
import com.summit.core.tool.ToolExecuteResult;

import java.io.Serializable;
import java.util.List;

public interface ConversationManager {

    void startConversation(AgentRequest agentRequest);

    void addMessage(Serializable sessionId, ChatResponseEntity chatResponse,  List<ToolExecuteResult> toolExecutionResultMessage);
    ConversationEntity endConversation(Serializable sessionId);

    List<Message> messages(Serializable sessionId);




    TokenUsageEntity tokenUsage(Serializable sessionId);

    void rebuildContext(ContextSummary contextSummary, Serializable sessionId);

    /**
     * Rebuilds a session from the given summary, telling whether the round that triggered the rebuild
     * was the model's own answer to the trailing user turn.
     *
     * <p>A model-initiated compaction ({@code compact_context}) <em>is</em> that answer: the round is
     * not persisted, so the request the model just served is still the newest message of the session.
     * Keeping it verbatim makes the model answer it a second time — a second compaction, then a third,
     * until the run gives up. Such a turn is therefore dropped and replaced by the
     * "continue after compaction" instruction.</p>
     *
     * <p>The default implementation keeps the trailing user turn: a checkpoint-driven compaction runs
     * before the model ever saw the request, so that request must survive.</p>
     */
    default void rebuildContext(ContextSummary contextSummary, Serializable sessionId, boolean answeredTrailingUserTurn) {
        rebuildContext(contextSummary, sessionId);
    }

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

    /** Refreshes the boundary while retaining request context embedded in the system prompt. */
    default void refreshBoundary(Serializable sessionId, LoopBoundary boundary, String customSystemPrompt,
                                 String task, List<String> toolList) {
        refreshBoundary(sessionId, boundary, customSystemPrompt);
    }

    /**
     * Refreshes the boundary using the live workspace already bound to the current execution.
     *
     * <p>This overload matters for callers that supply a {@link Workspace} directly instead of a
     * persistable workspace specification. In that case the conversation cannot resolve the
     * workspace again from storage while a plan is switching from PLANING to EXECUTE.</p>
     */
    default void refreshBoundary(Serializable sessionId, LoopBoundary boundary, String customSystemPrompt,
                                 String task, List<String> toolList, Workspace workspace) {
        refreshBoundary(sessionId, boundary, customSystemPrompt, task, toolList);
    }

    /**
     * Appends a plain user message to an existing session (used by the runtime to inject the
     * user's plan feedback / the approved plan before the next model round). Default no-op keeps
     * existing implementations intact.
     */
    default void appendUserMessage(Serializable sessionId, String text) {
    }

    /** Appends a framework instruction that has USER semantics for the model but is hidden from UI history. */
    default void appendInternalUserMessage(Serializable sessionId, String text) {
        appendUserMessage(sessionId, text);
    }
}
