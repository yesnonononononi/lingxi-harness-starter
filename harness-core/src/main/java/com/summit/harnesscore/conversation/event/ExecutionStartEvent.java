package com.summit.harnesscore.conversation.event;

import lombok.AllArgsConstructor;
import lombok.RequiredArgsConstructor;

import java.time.Instant;

@AllArgsConstructor
public class ExecutionStartEvent implements AgentEvent{
    private String executionId;

    @Override
    public String executionId() {
        return executionId;
    }

    @Override
    public Instant timestamp() {
        return Instant.now();
    }
}
