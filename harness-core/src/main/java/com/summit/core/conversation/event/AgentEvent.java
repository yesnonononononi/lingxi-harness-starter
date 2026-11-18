package com.summit.core.conversation.event;

/**
 * The streaming surface of one model response: incremental text and thinking deltas, the completed
 * text of a turn, and the final assistant message a renderer draws.
 *
 * <p>These events are the only ones emitted at token frequency, so they are shaped for that: see the
 * {@code record} implementations, allocated once per chunk. A consumer that persists them is a
 * replay buffer; a consumer that audits them wants {@link RuntimeEvent} instead.</p>
 *
 * <p>See {@link Event} for the discriminator and the execution identity every event carries.</p>
 */
public interface AgentEvent extends Event {
}
