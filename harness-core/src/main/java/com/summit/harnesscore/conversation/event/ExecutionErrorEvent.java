package com.summit.harnesscore.conversation.event;

import lombok.Data;

import java.sql.Timestamp;
import java.time.Instant;

@Data
public class ExecutionErrorEvent implements AgentEvent{
    private final Throwable err;
    private final String extraDes;
    private final String executionId;
    private final Timestamp timestamp;

    @Override
    public String executionId() {
        return executionId;
    }

    @Override
    public Instant timestamp() {
        return timestamp.toInstant()    ;
    }
}
