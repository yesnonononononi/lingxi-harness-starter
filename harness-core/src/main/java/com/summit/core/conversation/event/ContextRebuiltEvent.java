package com.summit.core.conversation.event;

import lombok.Builder;
import lombok.Getter;

import java.util.Map;

/**
 * The context was rebuilt from a summary: the history was replaced by a summary, the retained
 * dynamic sections and the trailing turn.
 *
 * <p>This is the most destructive context event there is — a rebuild always breaks the cached
 * prefix, whatever the prompt cache was holding — so it is reported separately from
 * {@link ContextChangedEvent} instead of being folded into it as one more "reason".</p>
 */
@Getter
public class ContextRebuiltEvent extends AbstractEvent {

    /** Summary generation after the rebuild; {@code 1} is the first summary of a session. */
    private final int summaryGeneration;

    /** Messages the rebuilt context holds, so a consumer can see how much history survived. */
    private final int messageCount;

    /** Which revision of the execution's context this rebuild created (per-execution, starting at one). */
    private final long contextRevision;

    @Builder
    private ContextRebuiltEvent(String executionId, Map<String, Object> metaData, long contextRevision,
                                int summaryGeneration, int messageCount) {
        super(executionId, metaData);
        this.contextRevision = contextRevision;

        this.summaryGeneration = summaryGeneration;
        this.messageCount = messageCount;
    }

    @Override
    public String type() {
        return RuntimeEventType.CONTEXT_REBUILT.type();
    }
}
