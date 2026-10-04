package com.summit.core.runtime.loop;

import com.summit.core.agent.Execution;

public interface ExecutionControl {
    void cancel(String executionId);
    void suspend(String executionId);
    Execution resume(Execution execution);
    /** Persist the approval's in-progress state before any external side effect. */
    void beginApproval(Execution execution);
    /** Persist the approval result before the normal loop may resume. */
    void finishApproval(Execution execution, ApprovalOutcome outcome);
    /** Persist a failed approval action without restarting it. */
    void failApproval(Execution execution, String errorMessage);
    /**
     * Resuming by id is not supported, and implementations must not simulate it by decoding the
     * stored snapshot. That would resume a detached copy instead of the suspended execution: the
     * suspended object itself would never resume, its identity would be lost for every observer
     * that already holds it, and the copy plus the original could overwrite each other's checkpoint.
     * Callers must hold the suspended {@link Execution} and pass it to {@link #resume(Execution)}.
     */
    default Execution resume(String executionId) {
        throw new UnsupportedOperationException("Resume by ID is not supported by this controller");
    }
}
