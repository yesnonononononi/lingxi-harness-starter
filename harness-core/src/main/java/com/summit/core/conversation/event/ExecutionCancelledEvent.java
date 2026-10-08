package com.summit.core.conversation.event;


import lombok.Getter;

import java.time.Instant;
import java.util.Map;

/**
 * Published when an agent-loop execution is cancelled (externally stopped
 * or interrupted) rather than completing on its own.
 */

@Getter
public class ExecutionCancelledEvent implements AgentEvent {
    private final String executionId;
    /** Usage accumulated before the cancellation; {@code null} = not collected (≠ zero). */
    private final TokenInfo tokenInfo;
    private final Instant timestamp = Instant.now();
    private final Map<String, Object> metaData;

    public ExecutionCancelledEvent(String executionId, TokenInfo tokenInfo, Map<String, Object> metaData) {
        this.executionId = executionId;
        this.tokenInfo = tokenInfo;
        this.metaData = metaData == null ? Map.of() : Map.copyOf(metaData);
    }

    /** Compatibility constructor for callers without token usage. */
    public ExecutionCancelledEvent(String executionId, Map<String, Object> metaData) {
        this(executionId, null, metaData);
    }

    /** Compatibility constructor for callers without selected event metadata. */
    public ExecutionCancelledEvent(String executionId) {
        this(executionId, null, Map.of());
    }
    @Override
    public String executionId() {
        return executionId;
    }

    @Override
    public Instant timestamp() {
        return timestamp;
    }
    @Override
    public Map<String, Object> eventMetaData() {
        return metaData;
    }
    @Override
    public String type() {
        return RuntimeEventType.EXECUTION_CANCELLED.type();
    }
}
