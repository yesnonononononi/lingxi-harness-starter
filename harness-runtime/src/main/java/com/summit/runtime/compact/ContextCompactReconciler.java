package com.summit.runtime.compact;

import com.summit.core.compact.CompactSummaryResolver;
import com.summit.core.compact.ContextSummary;
import com.summit.core.conversation.ConversationManager;
import com.summit.core.tool.ToolExecuteResult;
import com.summit.core.tool.ToolResultType;
import com.summit.runtime.agent.AgentLoopRunner;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

import java.io.Serializable;
import java.util.List;

/**
 * Reconciles a model-initiated {@code compact_context} call.
 *
 * <p>The compaction itself is not decided or performed here: the squeeze band is judged in
 * {@code CheckPointer#afterCheckpoint} and the work is done in a blocking way by a
 * {@code ContextCompacter}. This adapter only turns the raw tool output into the
 * {@link ContextSummary} the conversation rebuild needs, and is split out of
 * {@link AgentLoopRunner} so the loop stays free of compaction payload handling.</p>
 */
@RequiredArgsConstructor
@Slf4j
public class ContextCompactReconciler {

    private final ConversationManager conversationManager;

    /**
     * Rebuilds the conversation when this round contains a {@code compact_context} call; the round
     * itself is then not persisted.
     *
     * @return true when the round was a compact request (the caller skips the rest of the round)
     */
    public boolean reconcile(List<ToolExecuteResult> toolResults, Serializable sessionId) {
        ToolExecuteResult compactResult = toolResults.stream()
                .filter(ContextCompactReconciler::isCompactRequest)
                .findFirst()
                .orElse(null);
        if (compactResult == null) {
            return false;
        }
        conversationManager.rebuildContext(resolveSummary(compactResult), sessionId);
        return true;
    }

    /**
     * The summary to rebuild with; {@code null} when the raw output cannot be resolved — the rebuild
     * is then skipped by {@code ConversationManager} instead of corrupting the context.
     */
    private ContextSummary resolveSummary(ToolExecuteResult compactResult) {
        try {
            ContextSummary summary = CompactSummaryResolver.resolve(compactResult.getToolOutput());
            if (summary == null) {
                log.warn("【context-summary】compact model returned no usable summary, context rebuild is skipped");
            }
            return summary;
        } catch (Exception unexpected) {
            log.error("【context-summary】Unexpected error occurred while resolving context summary", unexpected);
            return null;
        }
    }

    private static boolean isCompactRequest(ToolExecuteResult result) {
        return result != null && ToolResultType.CONTEXT_COMPACT == result.getToolResultType();
    }
}
