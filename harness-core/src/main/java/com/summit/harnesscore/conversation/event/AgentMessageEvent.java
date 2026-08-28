package com.summit.harnesscore.conversation.event;

import lombok.Data;

import java.io.Serializable;
import java.time.Instant;

@Data
public class AgentMessageEvent implements AgentEvent{
    private final Serializable sessionId;
    private final String text;
    private final String thinking;
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
