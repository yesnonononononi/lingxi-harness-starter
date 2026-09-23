package com.summit.core.conversation.event;

import java.time.Instant;

/**
 * An execution-scoped runtime event.
 *
 * <p>Besides {@link #executionId()} / {@link #timestamp()}, every implementation must declare its
 * {@link RuntimeEventType}, so downstream transports can serialize a {@code type} discriminator
 * without maintaining hard-coded strings.</p>
 */
public interface AgentEvent extends TypedEvent {
    String executionId();

    Instant timestamp();
}
