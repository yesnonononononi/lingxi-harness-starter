package com.summit.core.conversation.event;

import com.fasterxml.jackson.annotation.JsonProperty;

import java.time.Instant;
import java.util.Map;

/**
 * The top-level contract of everything the runtime publishes.
 *
 * <p>Only the fields every event must carry live here: the {@link #type()} discriminator, the
 * execution it belongs to, the moment it happened and the caller-selected metadata. What an event
 * is <em>for</em> is expressed by the sub-interface, not by this one — {@link RuntimeEvent} for
 * lifecycle, tool and context facts a consumer audits or persists, {@link AgentEvent} for the
 * streaming surface a renderer draws. Keeping the split below this interface is what stops "agent
 * event" from quietly meaning "every event".</p>
 *
 * <p>{@link #type()} is the framework's own event kind, not a slot for caller-defined
 * orchestration: {@link RuntimeEventType} supplies the canonical constants, and the transport layer
 * serialises {@code type()} straight onto the wire.</p>
 */
public interface Event {

    /**
     * Discriminator used by listeners / transports to dispatch this event.
     *
     * <p>Annotated here, on the interface, together with the other three wire fields: the event kind
     * is part of the transport contract, not something every host has to re-declare by registering a
     * Jackson mix-in on its own mapper. A mapper that never heard of this framework still serialises
     * {@code type} — which matters the moment an application serialises events it received.
     * </p>
     */
    @JsonProperty("type")
    String type();

    @JsonProperty("executionId")
    String executionId();

    @JsonProperty("metaData")
    default Map<String, Object> eventMetaData() {
        return Map.of();
    }

    @JsonProperty("timestamp")
    Instant timestamp();
}
