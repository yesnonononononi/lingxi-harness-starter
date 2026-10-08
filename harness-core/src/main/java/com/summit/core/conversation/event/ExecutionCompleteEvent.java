package com.summit.core.conversation.event;


import lombok.Data;

import java.time.Instant;
import java.util.Map;

@Data
public class ExecutionCompleteEvent implements AgentEvent{
    private final String executionId;
    private final TokenInfo tokenInfo;
    private final Instant timestamp = Instant.now();
    private final Map<String, Object> metaData;


    public ExecutionCompleteEvent(String executionId, TokenInfo tokenInfo, Map<String, Object> metaData) {
        this.executionId = executionId;
        this.tokenInfo = tokenInfo;
        this.metaData = metaData == null ? Map.of() : Map.copyOf(metaData);
    }

    /** Compatibility constructor for callers without selected event metadata. */
    public ExecutionCompleteEvent(String executionId, TokenInfo tokenInfo) {
        this(executionId, tokenInfo, Map.of());
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
        return RuntimeEventType.EXECUTION_COMPLETED.type();
    }
}
