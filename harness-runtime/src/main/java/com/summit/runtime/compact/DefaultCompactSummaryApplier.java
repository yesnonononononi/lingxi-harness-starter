package com.summit.runtime.compact;

import com.summit.core.agent.Execution;
import com.summit.core.compact.CompactSummaryApplier;
import com.summit.core.conversation.CheckpointSaver;
import com.summit.core.conversation.ConversationManager;
import lombok.RequiredArgsConstructor;

import java.util.List;

/**
 * Applies a model-generated compaction output to a session.
 *
 * <p>Resolving the raw text, rendering the structured summary, and rebuilding the context are one
 * pipeline: the caller has an output string and needs to know only whether the context changed.
 * The summary layout and the prompt that produces it are the compaction strategy's business, so
 * they live beside this class rather than in the {@link ConversationManager} contract.</p>
 *
 * <p>The rebuild needs a checkpoint to publish behind, and persistence belongs to the runtime, so
 * the save action is carried here — resolved once when this applier is assembled — and handed to the
 * manager at the call. This class never sees the repository itself.</p>
 */
@RequiredArgsConstructor
public class DefaultCompactSummaryApplier implements CompactSummaryApplier {

    private final ConversationManager conversationManager;
    private final CheckpointSaver checkpointSaver;


    /**
     * Resolves the output and rebuilds the context, but only when a usable summary came out of it.
     *
     * @return {@code false} when the output was blank or unusable, leaving the context untouched
     */
    @Override
    public boolean apply(String rawOutput, Execution execution) {
        ContextSummary summary = CompactSummaryResolver.resolve(rawOutput);
        if (summary == null) return false;
        // The rebuild reports itself: which message list a session now has is the manager's domain,
        // and this class has nothing to say about it beyond "the summary was usable".
        conversationManager.rebuildContext(render(summary), execution, checkpointSaver);
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
                        overturned:
                        %s
                        unresolved:
                        %s
                        completed task:
                        %s
                        pending task:
                        %s
                        summary-task state:
                        %s
                        Continue the conversation based on this summary. Do NOT execute anything about this summary
                        """, summary.getGoal(), summary.getSummary(),
                summary.getOverturned() == null ? List.of() : summary.getOverturned(),
                summary.getUnresolved() == null ? List.of() : summary.getUnresolved(),
                summary.getCompleted() == null ? List.of() : summary.getCompleted(),
                summary.getPending() == null ? List.of() : summary.getPending(),
                summary.getState());
    }
}
