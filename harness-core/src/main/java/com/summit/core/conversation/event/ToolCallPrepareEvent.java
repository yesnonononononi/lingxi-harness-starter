package com.summit.core.conversation.event;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.Instant;
import java.util.Map;

@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class ToolCallPrepareEvent implements AgentEvent{
    private String executionId;
    private String toolCallId;
    private String agentId;
    private int index;
    private String responseId;
    private String toolName;
    private Map<String,Object> metaData;
    private String partialArguments;
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
    public Map<String, Object> eventMetaData() {
        return metaData;
    }

    @Override
    public String type() {
        return RuntimeEventType.TOOL_CALL_PREPARE.type();
    }
}
