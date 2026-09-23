package com.summit.core.compact;

import com.summit.core.agent.Execution;

/**
 * Context of one blocking compaction, built by the runtime checkpoint once a squeeze is needed.
 *
 * <p>{@link #decision()} carries the progressive squeeze decision that triggered it, letting each
 * compacter pick its effort (e.g. {@code DefaultManualCompacter} uses its {@code truncateTurn});
 * manual calls without a band (e.g. a direct command) may pass {@code null}.</p>
 *
 * @param execution the execution whose in-memory context is being compacted
 * @param decision    the triggering squeeze decision, may be {@code null}
 */
public record ContextCompactRequest(
        Execution execution,
        ContextSqueezeRequest decision
) {

    public ContextCompactRequest(Execution execution) {
        this(execution, null);
    }
}
