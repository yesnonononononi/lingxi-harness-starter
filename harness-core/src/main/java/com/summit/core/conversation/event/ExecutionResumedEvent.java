package com.summit.core.conversation.event;

import lombok.RequiredArgsConstructor;

import java.time.Instant;
@RequiredArgsConstructor
public class ExecutionResumedEvent implements AgentEvent{
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
        return RuntimeEventType.EXECUTION_RESUME.type();
    }
}
