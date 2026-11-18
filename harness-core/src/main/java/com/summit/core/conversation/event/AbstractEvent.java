package com.summit.core.conversation.event;

import lombok.Getter;

import java.time.Instant;
import java.util.Map;

/**
 * The three fields every framework event must carry, in one place.
 *
 * <p>Event classes that are not records extend this instead of restating {@code executionId},
 * {@code timestamp} and {@code metaData} — the restatement is not just boilerplate: it is how an
 * event ends up with a {@code null} metadata map or an inconsistent timestamp, and how a new event
 * forgets one of the three. Streaming events stay records on purpose (one instance per token chunk,
 * so allocation matters more than inheritance).</p>
 *
 * <p>Both accessor styles are exposed deliberately: the interface methods the transport calls, and
 * the JavaBean getters existing callers and serialisers already rely on.</p>
 */
@Getter
public abstract class AbstractEvent implements Event {

    private final String executionId;
    private final Instant timestamp;
    private final Map<String, Object> metaData;

    protected AbstractEvent(String executionId) {
        this(executionId, Map.of());
    }

    protected AbstractEvent(String executionId, Map<String, Object> metaData) {
        this.executionId = executionId;
        this.timestamp = Instant.now();
        this.metaData = metaData == null ? Map.of() : Map.copyOf(metaData);
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
}
