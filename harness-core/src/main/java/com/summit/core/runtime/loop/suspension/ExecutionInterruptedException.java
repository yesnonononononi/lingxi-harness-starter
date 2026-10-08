package com.summit.core.runtime.loop.suspension;

/**
 * Raised inside a streaming model call when a control request arrives mid-flight.
 *
 * <p>A cooperative control signal is only observable at loop boundaries, but a streaming model
 * call can run for a long time. This exception carries a mid-stream interrupt from the streaming
 * response handler back to the loop, so a suspend or cancel request does not have to wait for the
 * model to finish producing output that the caller already asked to abandon.</p>
 *
 * <p>It is a control-flow signal, not a failure: the loop translates it into
 * {@code LoopResult.suspended} / {@code LoopResult.cancelled} rather than an execution error.</p>
 */
public class ExecutionInterruptedException extends RuntimeException {

    /** The intention observed mid-stream, so the loop can distinguish suspend from cancel. */
    private final Kind kind;

    public enum Kind {
        SUSPEND,
        CANCEL
    }

    public ExecutionInterruptedException(Kind kind, String message) {
        super(message);
        this.kind = kind;
    }

    public Kind kind() {
        return kind;
    }
}
