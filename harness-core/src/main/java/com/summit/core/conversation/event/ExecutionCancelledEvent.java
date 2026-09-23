package com.summit.core.conversation.event;

import lombok.AllArgsConstructor;
import lombok.Getter;

import java.time.Instant;

/**
 * Published when an agent-loop execution is cancelled (externally stopped
 * or interrupted) rather than completing on its own.
 */
@AllArgsConstructor
@Getter
public class ExecutionCancelledEvent implements AgentEvent {
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
    public RuntimeEventType type() {
        return RuntimeEventType.EXECUTION_CANCELLED;
    }
}
