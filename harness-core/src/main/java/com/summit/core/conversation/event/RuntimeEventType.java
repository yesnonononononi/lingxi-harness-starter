package com.summit.core.conversation.event;

/**
 * Canonical discriminator of every agent runtime event.
 *
 * <p>Every event class exposes its own kind through {@link TypedEvent#type()}, so the transport
 * layer (SSE / WebSocket / message queue) never has to hard-code type strings again:
 * {@code event.type().name()} can be used directly as the wire value.</p>
 *
 * <p>Naming rule: the constant name IS the value pushed to clients, which keeps back-end and
 * front-end in sync without a translation table.</p>
 */
public enum RuntimeEventType {

    /** Reserved for events that cannot declare their own kind (never used by framework events). */
    UNSPECIFIED,

    EXECUTION_RESUME,

    /** Agent loop for one turn started. */
    EXECUTION_STARTED,
    /** Agent loop finished normally; may carry token usage. */
    EXECUTION_COMPLETED,
    /** Agent loop failed with an exception. */
    EXECUTION_FAILED,
    /** Agent loop stopped by the user / external interruption. */
    EXECUTION_CANCELLED,

    /** Streaming text delta from the model. High frequency, one per token chunk. */
    PARTIAL_TEXT,
    /** Streaming reasoning / thinking delta from the model. High frequency. */
    PARTIAL_THINKING,
    /** Final assistant message of a model round. */
    AI_MESSAGE,

    /** A tool is about to be invoked. */
    TOOL_CALL,
    /** A tool finished and produced an output. */
    TOOL_COMPLETED,

    /** Context window usage changed (compaction started / completed). */
    CONTEXT_UPDATE
}
