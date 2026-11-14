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
    /**
     * Persist a failed execution and return its notification task.
     * <p>The task delivers the failure notification through
     * {@link ExecutionRepository#afterCommit}, so running it only registers the delivery; an
     * implementation must not wrap it again — a nested registration made from inside a commit
     * callback is accepted by the transaction manager and then never invoked.</p>
     * The caller runs the task after any final usage reporting and cleanup.
     */
    Runnable fail(Execution execution, Exception cause);
    /** Persist a failed approval action without restarting it. */
    void failApproval(Execution execution, String errorMessage);

}
