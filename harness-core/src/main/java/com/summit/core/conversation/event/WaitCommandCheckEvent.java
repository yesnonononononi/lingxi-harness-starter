package com.summit.core.conversation.event;

import lombok.Data;

import java.time.Instant;
@Data
public class WaitCommandCheckEvent implements AgentEvent {
    private final String executionId;
    private final String toolExecutionId;
    private final String sessionId;
    private final String formatedToolCommand;
    @Override
    public String executionId() {
        return this.executionId;
    }

    @Override
    public Instant timestamp() {
        return null;
    }
}
