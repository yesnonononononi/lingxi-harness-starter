package com.summit.core.conversation.event;

import lombok.Builder;
import lombok.Getter;

import java.time.Instant;
import java.util.Map;
import java.util.concurrent.TimeUnit;

@Builder
@Getter
public class InvokeRetryEvent implements AgentEvent{
    private String executionId;
    private String responseId;
    @Builder.Default
    private Map<String, Object> metaData = Map.of();
    private int retryCount;
    private long retryDelay;
    private TimeUnit timeUnit;
    @Builder.Default
    private Instant timestamp = Instant.now();

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
        return metaData == null ? Map.of() : Map.copyOf(metaData);
    }

    @Override
    public String type() {
        return RuntimeEventType.INVOKE_RETRY.type();
    }
}
