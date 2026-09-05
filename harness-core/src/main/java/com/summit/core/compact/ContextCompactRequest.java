package com.summit.core.compact;

import java.io.Serializable;

/**
 * Context of one blocking compaction, built by the runtime checkpoint once a squeeze is needed.
 *
 * <p>{@link #decision()} carries the progressive squeeze decision that triggered it, letting each
 * compacter pick its effort (e.g. {@code DefaultManualCompacter} uses its {@code truncateTurn});
 * manual calls without a band (e.g. a direct command) may pass {@code null}.</p>
 *
 * @param sessionId   the session being compacted
 * @param executionId the execution that triggered this compaction
 * @param decision    the triggering squeeze decision, may be {@code null}
 */
public record ContextCompactRequest(
        Serializable sessionId,
        String executionId,
        ContextSqueezeRequest decision
) {

    public ContextCompactRequest(Serializable sessionId, String executionId) {
        this(sessionId, executionId, null);
    }
}
