package com.summit.core.conversation.event;

import lombok.AllArgsConstructor;
import lombok.Getter;

import java.time.Instant;

@AllArgsConstructor
@Getter
public class ExecutionStartEvent implements AgentEvent{
    private String executionId;
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
        return RuntimeEventType.EXECUTION_STARTED.type();
    }
}
