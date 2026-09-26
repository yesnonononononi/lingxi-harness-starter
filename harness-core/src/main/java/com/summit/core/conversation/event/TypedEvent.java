package com.summit.core.conversation.event;

/**
 * Anything that can be routed by kind when leaving the runtime boundary.
 *
 * <p>Kept separate from {@link AgentEvent} so events that are not tied to a single execution
 * can still declare a type.</p>
 */
public interface TypedEvent {

    /** Discriminator used by listeners / transports to dispatch this event. */
    String type();
}
