package com.summit.core.conversation.event;

import lombok.Data;


import java.time.Instant;
import java.util.Map;

@Data
public class ExecutionErrorEvent implements AgentEvent{
    private final String errMsg;
    private final String extraDes;
    private final String executionId;
    /** Usage accumulated before the failure; {@code null} = not collected (≠ zero). */
    private final TokenInfo tokenInfo;
    private final Map<String, Object> metaData;
    private final Instant timestamp = Instant.now();

    public ExecutionErrorEvent(String errMsg, String extraDes, String executionId, TokenInfo tokenInfo,
                               Map<String, Object> metaData) {
        this.errMsg = errMsg;
        this.extraDes = extraDes;
        this.executionId = executionId;
        this.tokenInfo = tokenInfo;
        this.metaData = metaData == null ? Map.of() : Map.copyOf(metaData);
    }

    /** Compatibility constructor for callers without token usage. */
    public ExecutionErrorEvent(String errMsg, String extraDes, String executionId, Map<String, Object> metaData) {
        this(errMsg, extraDes, executionId, null, metaData);
    }

    /** Compatibility constructor for callers without selected event metadata. */
    public ExecutionErrorEvent(String errMsg, String extraDes, String executionId) {
        this(errMsg, extraDes, executionId, null, Map.of());
    }
    @Override
    public Map<String, Object> eventMetaData() {
        return metaData;
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
        return RuntimeEventType.EXECUTION_FAILED.type();
    }
}
