package com.summit.core.conversation.event;

import lombok.Data;

import java.time.Instant;

@Data
public class AgentMessageEvent implements AgentEvent{
    private final String text;
    private final String thinking;
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
        return RuntimeEventType.AI_MESSAGE.type();
    }
}
