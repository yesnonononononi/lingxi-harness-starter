package com.summit.harnesscore.conversation.event;


import java.time.Instant;

public class NormalTextEvent implements AgentEvent {
    private final String executionId;
    private final Instant timestamp;

    public NormalTextEvent(String executionId, Instant timestamp) {
        this.executionId = executionId;
        this.timestamp = timestamp;
    }

    @Override
    public String executionId() {
        return this.executionId;
    }

    @Override
    public Instant timestamp() {
        return this.timestamp;
    }
}
