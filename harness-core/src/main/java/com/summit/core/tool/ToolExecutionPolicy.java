package com.summit.core.tool;

/**
 * Application admission policy evaluated on the loop thread before the tool timeout starts.
 * Returning a result rejects/short-circuits the call; returning {@code null} admits execution.
 * A policy can return a {@link ToolResultType#PROMISE} result when the surrounding execution must
 * stop after committing the current tool batch.
 */
@FunctionalInterface
public interface ToolExecutionPolicy {
    ToolExecuteResult beforeExecution(ToolExecution execution);

    default int order() {
        return 0;
    }
}
