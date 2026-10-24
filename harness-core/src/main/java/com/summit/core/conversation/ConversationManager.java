package com.summit.core.conversation;

import com.summit.core.agent.CompactionState;
import com.summit.core.agent.Execution;
import com.summit.core.conversation.message.Message;
import com.summit.core.conversation.message.SystemMessageEntity;
import com.summit.core.conversation.message.TokenUsageEntity;
import com.summit.core.mcp.McpToolScope;
import com.summit.core.runtime.loop.LoopMessages;
import com.summit.core.runtime.workspace.Workspace;

import java.util.List;

public interface ConversationManager {

    /**
     * Opens the context of one execution and lays down its leading system message.
     *
     * <p>The MCP scope is passed in rather than looked up: it is per-execution request data that
     * only the runtime holds, while this manager is a singleton shared by every execution. The
     * prompt needs it to publish the request's remote tools as résumés — see
     * {@link com.summit.core.prompt.PromptAssembler#withMcpToolPrompt}. An implementation with no
     * use for it may ignore the argument; a request without MCP servers passes the shared empty
     * scope, so the "no remote tools" case needs no special handling.</p>
     */
    void startConversation(Execution execution, Workspace workspace, McpToolScope mcpToolScope);

    /** Commits round messages; the runtime accounts for returned model usage before this call. */
    void addMessage(LoopMessages loopMessages);

    List<Message> messages(Execution execution);

    TokenUsageEntity tokenUsage(Execution execution);

    /**
     * Rebuilds a session from an already-resolved summary, telling whether the round that
     * triggered the rebuild was the model's own answer to the trailing user turn.
     *
     * <p>The summary arrives as text: what a compaction produces and how it is parsed belong to
     * the compaction strategy, so a session can be rebuilt from any source of one without this
     * contract adopting one summary layout.</p>
     */
    void rebuildContext(String summary, Execution execution, boolean answeredTrailingUserTurn);

    /**
     * Reads how far the context compaction of {@code execution} has progressed.
     *
     * <p>The state lives on the execution, not here: this manager is a singleton shared by every
     * execution, so it must not hold per-run state. It <em>changes</em> the execution it is handed
     * — {@link #rebuildContext} already replaces the execution's messages — and that is how "a
     * summary generation happened" is expressed without the manager keeping any record.</p>
     *
     * @param execution the execution whose compaction state is read
     * @return the current state, never {@code null}
     */
    default CompactionState compactionState(Execution execution) {
        return execution.getCompactionState();
    }



    /** Appends a plain user message to an existing session (used by the runtime to inject the the application's feedback / approved directive before the next model round). Default no-op keeps existing implementations intact. */
    default void appendUserMessage(Execution execution, String text) {
    }

    /**
     * Appends an instruction the framework addresses to the model — for example the directive a
     * loop hook returns when the user approved a plan — as a system message. It is not something
     * the user said, so it travels as a system prompt instead of a user turn: it never appears in
     * the user-visible history and is never mistaken for the user's newest request.
     * Default no-op keeps existing implementations intact.
     */
    default void appendSystemMessage(Execution execution, String text) {
    }

    void appendSystemMessage(Execution execution, SystemMessageEntity entity);

    void appendMessage(Execution execution, Message e);
}
