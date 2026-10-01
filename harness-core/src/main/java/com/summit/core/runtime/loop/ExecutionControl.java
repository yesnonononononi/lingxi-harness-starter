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
    default Execution resume(String executionId) {
        throw new UnsupportedOperationException("Resume by ID is not supported by this controller");
    }
}
