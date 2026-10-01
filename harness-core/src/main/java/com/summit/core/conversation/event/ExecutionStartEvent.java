package com.summit.core.conversation.event;


import lombok.Getter;

import java.time.Instant;
import java.util.Map;


@Getter
public class ExecutionStartEvent implements AgentEvent{
    private String executionId;
    private final Map<String, Object> metaData;

    private final Instant timestamp = Instant.now();

    public ExecutionStartEvent(String executionId, Map<String, Object> metaData) {
        this.executionId = executionId;
        this.metaData = metaData == null ? Map.of() : Map.copyOf(metaData);
    }

    /** Compatibility constructor for callers without selected event metadata. */
    public ExecutionStartEvent(String executionId) {
        this(executionId, Map.of());
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
        return RuntimeEventType.EXECUTION_STARTED.type();
    }
}
