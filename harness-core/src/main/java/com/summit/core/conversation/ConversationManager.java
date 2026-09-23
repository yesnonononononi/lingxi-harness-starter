package com.summit.core.conversation;

import com.summit.core.agent.Execution;
import com.summit.core.compact.ContextSummary;
import com.summit.core.conversation.api.ChatResponseEntity;
import com.summit.core.conversation.message.Message;
import com.summit.core.conversation.message.TokenUsageEntity;
import com.summit.core.runtime.workspace.Workspace;
import com.summit.core.tool.ToolExecuteResult;

import java.util.List;

public interface ConversationManager {

    void startConversation(Execution execution, Workspace workspace);

    void addMessage(Execution execution, ChatResponseEntity chatResponse, List<ToolExecuteResult> toolExecutionResultMessage);

    List<Message> messages(Execution execution);

    TokenUsageEntity tokenUsage(Execution execution);

    void rebuildContext(ContextSummary contextSummary, Execution execution);

    /** Rebuilds a session from the given summary, telling whether the round that triggered the rebuild was the model's own answer to the trailing user turn. */
    default void rebuildContext(ContextSummary contextSummary, Execution execution, boolean answeredTrailingUserTurn) {
        rebuildContext(contextSummary, execution);
    }

    /** Refreshes the leading system message of an existing session so the advertised tool list matches the tool set the run is now confined to. Used when a loop hook switches the run to another tool set — for example once the user approved a plan and the modifying tools become available. Default no-op keeps existing implementations intact. */
    default void refreshToolSet(Execution execution, String customSystemPrompt, String task,
                                List<String> toolList) {
    }

    /** Refreshes the tool set using the live workspace already bound to the current execution. */
    default void refreshToolSet(Execution execution, String customSystemPrompt, String task,
                                List<String> toolList, Workspace workspace) {
        refreshToolSet(execution, customSystemPrompt, task, toolList);
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
}
