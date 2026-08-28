package com.summit.harnesscore.conversation.event;


import lombok.Builder;
import lombok.Data;

import java.io.Serializable;
import java.time.Instant;

@Data
public class ExecutionCompleteEvent implements AgentEvent{
    @Builder
    public record TokenInfo(Integer inputTokenCount, Integer outputTokenCount, Integer totalTokenCount){}
    private final String executionId;
    private final Serializable sessionId;
    private final TokenInfo tokenInfo   ;

    @Override
    public String executionId() {
        return executionId;
    }

    @Override
    public Instant timestamp() {
        return Instant.now();
    }
}
