package com.summit.core.conversation.event;

import lombok.Data;

import java.io.Serializable;
import java.sql.Timestamp;
import java.time.Instant;

@Data
public class ExecutionErrorEvent implements AgentEvent{
    private final Throwable err;
    private final String extraDes;
    private final String executionId;
    private final Timestamp timestamp;
    private final Serializable sessionId;

    @Override
    public String executionId() {
        return executionId;
    }

    @Override
    public Instant timestamp() {
        return timestamp.toInstant()    ;
    }
}
