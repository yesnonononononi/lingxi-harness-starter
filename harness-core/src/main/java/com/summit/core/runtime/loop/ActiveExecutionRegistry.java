package com.summit.core.runtime.loop;

/** Registry used to address cooperative control signals of executions that are running now. */
public interface ActiveExecutionRegistry {
    ExecutionControlSignal register(String executionId);

    void unregister(ExecutionControlSignal signal);

    void requireSuspend(String executionId);

    void requireCancel(String executionId);
}
