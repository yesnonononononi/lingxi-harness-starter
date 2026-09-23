package com.summit.core.runtime.loop;

/** Terminal result of one loop run. There is no resumable program-position state. */
public record LoopResult(
        Status status,
        String message,
        boolean writeToolExecuted
) {
    public enum Status {
        CANCELLED,
        SUSPENDED,
        COMPLETED
    }

    public static LoopResult completed(boolean writeToolExecuted) {
        return new LoopResult(Status.COMPLETED, null, writeToolExecuted);
    }

    public static LoopResult cancelled(String reason, boolean writeToolExecuted) {
        return new LoopResult(Status.CANCELLED, reason, writeToolExecuted);
    }

    public static LoopResult suspended(String reason, boolean writeToolExecuted) {
        return new LoopResult(Status.SUSPENDED, reason, writeToolExecuted);
    }
}
