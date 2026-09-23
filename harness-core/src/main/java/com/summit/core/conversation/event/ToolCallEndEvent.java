package com.summit.core.conversation.event;

import com.summit.core.tool.ToolCallStatus;
import lombok.Data;

import java.time.Instant;

@Data
public class ToolCallEndEvent implements ToolCallEvent {
    private final String requestId;
    private final String executionId;
    private final String toolName;
    private final String args;
    private final String output;
    private final ToolCallStatus resultStatus;
    private final Instant timestamp = Instant.now();

    @Override
    public ToolCallStatus resultStatus() {
        return resultStatus;
    }

    @Override
    public String executionId() {
        return executionId;
    }

    @Override
    public Instant timestamp() {
        return timestamp;
    }

    @Override
    public RuntimeEventType type() {
        return RuntimeEventType.TOOL_COMPLETED;
    }
}
