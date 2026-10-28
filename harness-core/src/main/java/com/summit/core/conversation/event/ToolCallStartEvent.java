package com.summit.core.conversation.event;

import com.summit.core.tool.ToolCallStatus;
import lombok.Data;

import java.time.Instant;
import java.util.Map;

@Data
public class ToolCallStartEvent implements ToolCallEvent {
    private final String requestId;
    private final String executionId;
    private final String toolName;
    private final String args;
    private final String responseId;
    private final Map<String, Object> metaData;
    private final ToolCallStatus resultStatus = ToolCallStatus.STARTED;
    private final Instant timestamp = Instant.now();
    private final int requestIndex;
    public ToolCallStartEvent(String requestId, String executionId, String toolName, String args,String responseId, Map<String, Object> metaData, int requestIndex) {
        this.requestId = requestId;
        this.executionId = executionId;
        this.toolName = toolName;
        this.args = args;
        this.responseId = responseId;
        this.metaData = metaData == null ? Map.of() : Map.copyOf(metaData);
        this.requestIndex = requestIndex;
    }

    /** Compatibility constructor for callers without selected event metadata. */
    public ToolCallStartEvent(String requestId, String executionId, String toolName, String args,String responseId,int requestIndex) {
        this(requestId, executionId, toolName, args, responseId, Map.of(), requestIndex);
    }
    @Override
    public Map<String, Object> eventMetaData() {
        return metaData;
    }
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
    public String type() {
        return RuntimeEventType.TOOL_CALL.type();
    }
}
