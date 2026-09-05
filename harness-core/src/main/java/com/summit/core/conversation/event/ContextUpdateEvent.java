package com.summit.core.conversation.event;

import com.summit.core.compact.ContextUsageMetric;
import lombok.Data;

import java.io.Serializable;
import java.time.Instant;

/**
 * Context-compaction progress event, published twice by each {@code ContextCompacter} implementation
 * (manual per-round truncation / model deep compaction) during its blocking flow:
 * <ul>
 *   <li>{@link Phase#SQUEEZE_STARTED} squeeze started: {@link #usage} holds the usage before compaction;</li>
 *   <li>{@link Phase#SQUEEZE_COMPLETED} squeeze finished: {@link #usage} holds the usage after compaction.</li>
 * </ul>
 *
 * <p>Both events carry {@code tokenCount / maxTokens / ratio} so the front-end can render how the
 * "context usage" evolved (e.g. a gauge).</p>
 */
@Data
public class ContextUpdateEvent implements AgentEvent {

    /** Squeeze phase. */
    public enum Phase {
        /** Squeeze started. */
        SQUEEZE_STARTED,
        /** Squeeze finished (conversation context rewritten). */
        SQUEEZE_COMPLETED
    }

    private final Serializable sessionId;
    private final String executionId;
    /** Event phase: squeeze started / rebuild finished. */
    private final Phase phase;
    /** Context usage metric (tokenCount / maxTokens / ratio); null when no context cap is configured. */
    private final ContextUsageMetric usage;
    /** Human-readable progress message for the UI and logs (optional). */
    private final String message;

    @Override
    public String executionId() {
        return executionId;
    }

    @Override
    public Instant timestamp() {
        return Instant.now();
    }
}
