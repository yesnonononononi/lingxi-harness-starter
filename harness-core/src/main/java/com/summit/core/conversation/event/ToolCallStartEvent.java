package com.summit.core.conversation.event;

import com.summit.core.tool.ToolCallStatus;
import lombok.Data;

import java.time.Instant;
import java.util.Map;
import java.util.UUID;

@Data
public class ToolCallStartEvent implements ToolCallEvent {
    private final String requestId;
    private final String executionId;
    private final String toolName;
    private final String args;
    private final UUID responseId;
    private final Map<String, Object> metaData;
    private final ToolCallStatus resultStatus = ToolCallStatus.STARTED;
    private final Instant timestamp = Instant.now();

    public ToolCallStartEvent(String requestId, String executionId, String toolName, String args,UUID responseId, Map<String, Object> metaData) {
        this.requestId = requestId;
        this.executionId = executionId;
        this.toolName = toolName;
        this.args = args;
        this.responseId = responseId;
        this.metaData = metaData == null ? Map.of() : Map.copyOf(metaData);
    }

    /** Compatibility constructor for callers without selected event metadata. */
    public ToolCallStartEvent(String requestId, String executionId, String toolName, String args,UUID responseId) {
        this(requestId, executionId, toolName, args, responseId, Map.of());
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
