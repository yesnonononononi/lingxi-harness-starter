package com.summit.core.compact;

import com.summit.core.agent.Execution;

/**
 * Context of one blocking compaction, built by the runtime checkpoint once a squeeze is needed.
 *
 * <p>{@link #decision()} carries the squeeze decision that triggered it, letting each compacter know
 * which band asked for it; calls without a decision (e.g. the {@code compact_context} tool running
 * on its own) may pass {@code null}.</p>
 *
 * @param execution the execution whose in-memory context is being compacted
 * @param decision  the triggering squeeze decision, may be {@code null}
 */
public record ContextCompactRequest(
        Execution execution,
        ContextSqueezeRequest decision
) {

    public ContextCompactRequest(Execution execution) {
        this(execution, null);
    }
}
