package com.summit.core.conversation.event;

import lombok.Data;

import java.sql.Timestamp;
import java.time.Instant;

@Data
public class ExecutionErrorEvent implements AgentEvent{
    private final String errMsg;
    private final String extraDes;
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
        return RuntimeEventType.EXECUTION_FAILED.type();
    }
}
