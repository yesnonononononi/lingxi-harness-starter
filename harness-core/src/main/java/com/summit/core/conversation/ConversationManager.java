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
     * Opens the context of one execution: lays down its leading system message and this round's
     * dynamic context, and reports what it changed.
     *
     * <p>The MCP scope is passed in rather than looked up: it is per-execution request data that
     * only the runtime holds, while this manager is a singleton shared by every execution. The
     * prompt needs it to publish the request's remote tools as résumés — see
     * {@link com.summit.core.mcp.McpToolScope}. An implementation with no use for it may
     * ignore the argument; a request without MCP servers passes the shared empty scope, so the
     * "no remote tools" case needs no special handling.</p>
     *
     * <p><b>The manager publishes its own context facts.</b> The message list the model is about to
     * receive <em>is</em> this manager's domain, so the notification that it changed belongs to the
     * owner of the change: it is delivered here, after the change is applied, through the
     * {@code ContextEventPublisher} this manager was built with. Returning the event for someone
     * else to publish made every caller responsible for a decision it had no state to make — and the
     * one caller that did keep that responsibility dispatched a {@code null} on the common
     * "nothing changed" round.</p>
     *
     * <p><b>{@code checkpoint} is saved before the fact is published.</b> A listener may therefore
     * read the event as an authoritative fact: the session it describes is already stored, not "will
     * be stored if the run survives". Persistence itself stays the caller's: this manager never sees
     * a repository, it just calls the save action it was handed at the one moment that matters. The
     * saver runs only when there is a fact to publish — a round that changed nothing publishes
     * nothing and calls nothing — and it must complete synchronously. If it throws, the exception
     * propagates and no event is published: a fact about a session that failed to save is exactly
     * what this ordering exists to prevent.</p>
     *
     * @param checkpoint the save action that makes this round's context durable; production wiring
     *                   passes the runtime's repository, an in-process assembly passes
     *                   {@link CheckpointSaver#NONE} and accepts that its facts have no stored
     *                   session behind them
     */
    void startConversation(Execution execution, Workspace workspace, McpToolScope mcpToolScope,
                           CheckpointSaver checkpoint);

    /** Commits round messages; the runtime accounts for returned model usage before this call. */
    void addMessage(LoopMessages loopMessages);

    List<Message> messages(Execution execution);

    TokenUsageEntity tokenUsage(Execution execution);

    /**
     * Rebuilds a session from an already-resolved summary, reporting the rebuild as a context fact.
     *
     * <p>The summary arrives as text: what a compaction produces and how it is parsed belong to
     * the compaction strategy, so a session can be rebuilt from any source of one without this
     * contract adopting one summary layout.</p>
     *
     * <p>Like {@link #startConversation}, the fact is published by this manager: a rebuild always
     * breaks the cached prefix, and the notification that it happened is part of doing it, not a
     * separate chore for whoever happens to call this method.</p>
     *
     * <p>The same ordering holds: the rebuilt message list is saved through {@code checkpoint}
     * before the rebuild is published, so a consumer of {@code CONTEXT_REBUILT} is reading a session
     * that already exists in the store. A blank summary rebuilds nothing, publishes nothing and
     * saves nothing.</p>
     *
     * @param summary    the rendered summary to open the rebuilt session with; blank means there is
     *                   nothing to rebuild and no fact to report
     * @param checkpoint the save action that makes the rebuilt session durable
     */
    void rebuildContext(String summary, Execution execution, CheckpointSaver checkpoint);

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
