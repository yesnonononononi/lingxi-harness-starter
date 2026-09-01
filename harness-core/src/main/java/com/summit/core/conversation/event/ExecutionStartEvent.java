package com.summit.core.conversation.event;

import lombok.AllArgsConstructor;
import lombok.Getter;

import java.io.Serializable;
import java.time.Instant;

@AllArgsConstructor
@Getter
public class ExecutionStartEvent implements AgentEvent{
    private String executionId;
    Serializable sessionId;

    @Override
    public String executionId() {
        return executionId;
    }

    @Override
    public Instant timestamp() {
        return Instant.now();
    }
}
