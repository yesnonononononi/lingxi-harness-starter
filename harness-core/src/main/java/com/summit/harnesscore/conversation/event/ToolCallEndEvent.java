package com.summit.harnesscore.conversation.event;

import lombok.Builder;
import lombok.Data;

import java.time.Instant;
@Data
public class ToolCallEndEvent implements AgentEvent{
    private final String executionId;
    private final String toolName;
    private final String args;
    private final String output;
    @Override
    public String executionId() {
        return executionId;
    }

    @Override
    public Instant timestamp() {
        return Instant.now();
    }
}
