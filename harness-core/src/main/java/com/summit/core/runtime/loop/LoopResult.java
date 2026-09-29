package com.summit.core.runtime.loop;

/** Terminal result of one loop run. There is no resumable program-position state. */
public record LoopResult(
        Status status,
        String message
) {
    public enum Status {
        CANCELLED,
        SUSPENDED,
        COMPLETED
    }

    public static LoopResult completed() {
        return new LoopResult(Status.COMPLETED, null);
    }

    public static LoopResult cancelled(String reason) {
        return new LoopResult(Status.CANCELLED, reason);
    }

    public static LoopResult suspended(String reason) {
        return new LoopResult(Status.SUSPENDED, reason);
    }
}
