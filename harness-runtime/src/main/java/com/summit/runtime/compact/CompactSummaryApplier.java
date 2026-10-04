package com.summit.runtime.compact;

import com.summit.core.agent.Execution;
import com.summit.core.conversation.ConversationManager;

/**
 * Applies a model-generated compaction output to a session.
 *
 * <p>Resolving the raw text, rendering the structured summary, and rebuilding the context are one
 * pipeline: the caller has an output string and needs to know only whether the context changed.
 * The summary layout and the prompt that produces it are the compaction strategy's business, so
 * they live beside this class rather than in the {@link ConversationManager} contract.</p>
 */
public final class CompactSummaryApplier {

    private final ConversationManager conversationManager;

    public CompactSummaryApplier(ConversationManager conversationManager) {
        this.conversationManager = conversationManager;
    }

    /**
     * Resolves the output and rebuilds the context, but only when a usable summary came out of it.
     *
     * @return {@code false} when the output was blank or unusable, leaving the context untouched
     */
    public boolean apply(String rawOutput, Execution execution, boolean answeredTrailingUserTurn) {
        ContextSummary summary = CompactSummaryResolver.resolve(rawOutput);
        if (summary == null) return false;
        conversationManager.rebuildContext(render(summary), execution, answeredTrailingUserTurn);
        return true;
    }

    /** Renders one resolved summary as the system message the rebuilt session opens with. */
    private static String render(ContextSummary summary) {
        return String.format("""
                        The compact_context tool has been executed successfully, and the conversation history has been compressed into the following summary:
                        goal:
                        %s
                        summary:
                        %s
                        completed task:
                        %s
                        pending task:
                        %s
                        summary-task state:
                        %s
                        Continue the conversation based on this summary. Do NOT execute anything about this summary
                        """, summary.getGoal(), summary.getSummary(), summary.getCompleted(), summary.getPending(),
                summary.getState());
    }
}
