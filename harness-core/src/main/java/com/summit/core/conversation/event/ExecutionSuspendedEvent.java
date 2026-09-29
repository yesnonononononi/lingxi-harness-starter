package com.summit.core.conversation.event;

import lombok.AllArgsConstructor;
import lombok.Getter;

import java.time.Instant;

/**
 * Published when an agent-loop execution is suspended cooperatively instead of
 * running to a terminal state.
 *
 * <p>Suspension is not terminal: the execution keeps its committed round and its
 * request-scoped resources until a later resume or cancel. The counterpart
 * {@link ExecutionResumedEvent} is published when that resume happens.</p>
 */
@AllArgsConstructor
@Getter
public class ExecutionSuspendedEvent implements AgentEvent {
    private final String executionId;
    private final Instant timestamp = Instant.now();

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
