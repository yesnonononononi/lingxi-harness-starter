package com.summit.harnesscore.conversation.event;

import lombok.Data;

import java.time.Instant;

@Data
public class ExecutionCompleteEvent implements AgentEvent{
    private final String executionId;

    @Override
    public String executionId() {
        return executionId;
    }

    @Override
    public Instant timestamp() {
        return Instant.now();
    }
}
