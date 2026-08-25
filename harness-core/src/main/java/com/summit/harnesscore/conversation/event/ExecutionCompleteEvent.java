package com.summit.harnesscore.conversation.event;

import dev.langchain4j.model.output.TokenUsage;
import lombok.Data;

import java.time.Instant;

@Data
public class ExecutionCompleteEvent implements AgentEvent{
    private final String executionId;
    private final TokenUsage tokenUsage;

    @Override
    public String executionId() {
        return executionId;
    }

    @Override
    public Instant timestamp() {
        return Instant.now();
    }
}
