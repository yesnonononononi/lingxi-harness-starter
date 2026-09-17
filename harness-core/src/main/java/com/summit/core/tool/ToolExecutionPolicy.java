package com.summit.core.tool;

/**
 * Application admission policy evaluated on the loop thread before the tool timeout starts.
 * Returning a result rejects/short-circuits the call; returning {@code null} admits execution.
 * Policies may use {@code LoopSuspender} for human approval without consuming the tool's own
 * execution timeout.
 */
@FunctionalInterface
public interface ToolExecutionPolicy {
    ToolExecuteResult beforeExecution(ToolExecution execution);

    default int order() {
        return 0;
    }
}
