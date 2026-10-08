package com.summit.core.conversation.event;


import lombok.Getter;

import java.time.Instant;
import java.util.Map;

/**
 * Published when an agent-loop execution is suspended cooperatively instead of
 * running to a terminal state.
 *
 * <p>Suspension is not terminal: the execution keeps its committed round and its
 * request-scoped resources until a later resume or cancel. The counterpart
 * {@link ExecutionResumedEvent} is published when that resume happens.</p>
 */

@Getter
public class ExecutionSuspendedEvent implements AgentEvent {
    private final String executionId;
    private final Map<String, Object> metaData;
    private final Instant timestamp = Instant.now();

    public ExecutionSuspendedEvent(String executionId, Map<String, Object> metaData) {
        this.executionId = executionId;
        this.metaData = metaData == null ? Map.of() : Map.copyOf(metaData);
    }

    /** Compatibility constructor for callers without selected event metadata. */
    public ExecutionSuspendedEvent(String executionId) {
        this(executionId, Map.of());
    }
    @Override
    public Map<String, Object> eventMetaData() {
        return metaData;
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
    public String type() {
        return RuntimeEventType.EXECUTION_SUSPENDED.type();
    }
}
