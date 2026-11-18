package com.summit.core.conversation.event;

import com.summit.core.compact.ContextUsageMetric;
import lombok.EqualsAndHashCode;
import lombok.Getter;
import lombok.ToString;

import java.util.Map;

/**
 * Context-compaction progress event, published twice by each {@code ContextCompacter} implementation
 * (local history pruning / model deep compaction) during its blocking flow:
 * <ul>
 *   <li>{@link Phase#SQUEEZE_STARTED} squeeze started: {@link #usage} holds the usage before compaction;</li>
 *   <li>{@link Phase#SQUEEZE_COMPLETED} squeeze flow finished: {@link #usage} holds the usage after
 *       compaction (unchanged when the pass rewrote nothing).</li>
 * </ul>
 *
 * <p>Both events carry {@code tokenCount / maxTokens / ratio} so the front-end can render how the
 * "context usage" evolved (e.g. a gauge), and the {@link Band} that produced them so consumers can
 * decide per band what to do with the pair — the prune band finishes in milliseconds and most
 * consumers should not render it as an in-flight compaction.</p>
 */
@Getter
@EqualsAndHashCode(callSuper = true)
@ToString(callSuper = true)
public class ContextUpdateEvent extends AbstractEvent implements RuntimeEvent {

    /** Squeeze phase. */
    public enum Phase {
        UPDATE,
        /** Squeeze started. */
        SQUEEZE_STARTED,
        /** Squeeze flow finished (the context may or may not have been rewritten; the message tells). */
        SQUEEZE_COMPLETED
    }

    /**
     * Compaction band. Every squeeze event names the band that produced it; plain usage telemetry
     * ({@link Phase#UPDATE}) carries none.
     */
    public enum Band {
        /** Local history pruning: tool payloads are graded and trimmed, no model call involved. */
        PRUNE,
        /** Model deep compaction: the context is rebuilt from a generated summary. */
        SUMMARY
    }

    /** Event phase: squeeze started / rebuild finished. */
    private final Phase phase;

    /** Compaction band that produced this event; {@code null} on plain usage telemetry. */
    private final Band band;

    /** Context usage metric (tokenCount / maxTokens / ratio); null when no context cap is configured. */
    private final ContextUsageMetric usage;

    /** Human-readable progress message for the UI and logs (optional). */
    private final String message;

    public ContextUpdateEvent(String executionId, Phase phase, ContextUsageMetric usage, String message,
                              Map<String, Object> metaData) {
        this(executionId, phase, null, usage, message, metaData);
    }

    public ContextUpdateEvent(String executionId, Phase phase, Band band, ContextUsageMetric usage,
                              String message, Map<String, Object> metaData) {
        super(executionId, metaData);
        this.phase = phase;
        this.band = band;
        this.usage = usage;
        this.message = message;
    }

    /** Compatibility constructor for callers without selected event metadata. */
    public ContextUpdateEvent(String executionId, Phase phase, ContextUsageMetric usage, String message) {
        this(executionId, phase, usage, message, Map.of());
    }

    @Override
    public String type() {
        return RuntimeEventType.CONTEXT_UPDATE.type();
    }
}
