package com.summit.core.runtime.loop;

import com.summit.core.agent.Execution;

public interface ExecutionControl {
    void cancel(String executionId);
    void suspend(String executionId);
    Execution resume(Execution execution);
    default Execution resume(String executionId) {
        throw new UnsupportedOperationException("Resume by ID is not supported by this controller");
    }
}
