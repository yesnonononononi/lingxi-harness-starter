package com.summit.core.conversation.event;

import com.fasterxml.jackson.annotation.JsonProperty;

import java.time.Instant;
import java.util.Map;


/**
 * An execution-scoped runtime event.
 *
 * <p>Besides {@link #executionId()} / {@link #timestamp()}, every implementation must declare its
 * {@link RuntimeEventType}, so downstream transports can serialize a {@code type} discriminator
 * without maintaining hard-coded strings.</p>
 */
public interface AgentEvent extends TypedEvent {
    @JsonProperty("executionId")
    String executionId();

    @JsonProperty("metaData")
    default Map<String,Object> eventMetaData(){
        return Map.of();
    };

    @JsonProperty("timestamp")
    Instant timestamp();
}
