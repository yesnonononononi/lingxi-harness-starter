package com.summit.core.conversation.event;

/**
 * Anything that can be routed by kind when leaving the runtime boundary.
 *
 * <p>Kept separate from {@link AgentEvent} so events that are not tied to a single execution
 * can still declare a type.</p>
 *
 * <p>The discriminator is the framework's own event kind, not a slot for caller-defined
 * orchestration: {@link RuntimeEventType} supplies the canonical constants and the transport layer
 * serialises {@code event.type()} straight onto the wire. Declaring a type carries no orchestration
 * semantics of its own.</p>
 */
public interface TypedEvent {

    /** Discriminator used by listeners / transports to dispatch this event. */
    String type();
}
