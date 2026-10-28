package com.summit.core.conversation.event;

import com.summit.core.tool.ToolCallStatus;
import lombok.Data;

import java.time.Instant;
import java.util.Map;

@Data
public class ToolCallEndEvent implements ToolCallEvent {
    private final String requestId;
    private final String executionId;
    private final String responseId;
    private final String toolName;
    private final String args;
    private final String output;
    private final Map<String, Object> metaData;
    private final ToolCallStatus resultStatus;
    private final Instant timestamp = Instant.now();
    private final int requestIndex;
    public ToolCallEndEvent(String requestId, String executionId,String responseId, String toolName, String args, String output, Map<String, Object> metaData, ToolCallStatus resultStatus,int requestIndex) {
        this.requestId = requestId;
        this.executionId = executionId;
        this.responseId = responseId;
        this.requestIndex = requestIndex;
        this.toolName = toolName;
        this.args = args;
        this.output = output;
        this.metaData = metaData == null ? Map.of() : Map.copyOf(metaData);
        this.resultStatus = resultStatus;
    }

    /** Compatibility constructor for callers without selected event metadata. */
    public ToolCallEndEvent(String requestId, String executionId,String responseId, String toolName, String args, String output, ToolCallStatus resultStatus,int requestIndex) {
        this(requestId, executionId, responseId, toolName, args, output, Map.of(), resultStatus,requestIndex);
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
        return RuntimeEventType.TOOL_COMPLETED.type();
    }
}
