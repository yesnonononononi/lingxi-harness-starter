package com.summit.core.runtime.loop;

/** The effect of a completed external approval action on the execution. */
public enum ApprovalOutcome {
    /** The tool result is ready; the execution can return to its suspended checkpoint. */
    CONTINUE,
    /** Cancellation was requested while the approved action was being handled. */
    CANCELLED
}
