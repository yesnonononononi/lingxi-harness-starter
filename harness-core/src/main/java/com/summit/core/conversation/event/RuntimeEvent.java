package com.summit.core.conversation.event;

/**
 * System-level facts: execution lifecycle, tool calls, retries, token audits and everything that
 * happens to the context.
 *
 * <p>These are the events a consumer audits, persists or reacts to — an execution started, a tool
 * completed, the context changed. They are not a rendering stream: nothing here fires once per token
 * chunk, and a dropped event is a gap in the record rather than a missing frame.</p>
 *
 * <p>See {@link Event} for the discriminator and the execution identity every event carries.</p>
 */
public interface RuntimeEvent extends Event {
}
