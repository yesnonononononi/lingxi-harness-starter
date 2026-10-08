package com.summit.core.conversation.event;



import java.time.Instant;
import java.util.Map;


public class ExecutionResumedEvent implements AgentEvent{
    private final String executionId;
    private final Map<String, Object> metaData;
    private final Instant timestamp = Instant.now();

    public ExecutionResumedEvent(String executionId, Map<String, Object> metaData) {
        this.executionId = executionId;
        this.metaData = metaData == null ? Map.of() : Map.copyOf(metaData);
    }

    /** Compatibility constructor for callers without selected event metadata. */
    public ExecutionResumedEvent(String executionId) {
        this(executionId, Map.of());
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
    public Map<String, Object> eventMetaData() {
        return metaData;
    }
    @Override
    public String type() {
        return RuntimeEventType.EXECUTION_RESUME.type();
    }
}
