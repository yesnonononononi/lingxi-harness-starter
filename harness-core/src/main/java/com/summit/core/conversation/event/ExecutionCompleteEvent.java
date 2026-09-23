package com.summit.core.conversation.event;


import lombok.Builder;
import lombok.Data;

import java.time.Instant;

@Data
public class ExecutionCompleteEvent implements AgentEvent{
    @Builder
    public record TokenInfo(Integer inputTokenCount, Integer outputTokenCount, Integer totalTokenCount){}
    private final String executionId;
    private final TokenInfo tokenInfo;
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
    public RuntimeEventType type() {
        return RuntimeEventType.EXECUTION_COMPLETED;
    }
}
