package com.summit.core.runtime.loop;

import com.summit.core.agent.Execution;
import com.summit.core.agent.ExecutionState;

import java.util.Objects;

/** Required execution state changes, independent of replaceable lifecycle observers. */
public final class ExecutionTransitions {
    private ExecutionTransitions() {
    }

    public static void start(Execution execution) {
        require(execution, ExecutionState.CREATED);
        execution.start();
    }

    public static void resume(Execution execution) {
        require(execution, ExecutionState.SUSPENDED);
        execution.resume();
    }

    public static void suspend(Execution execution) {
        require(execution, ExecutionState.RUNNING);
        execution.suspended();
    }

    public static void complete(Execution execution) {
        require(execution, ExecutionState.RUNNING);
        execution.complete();
    }

    public static void cancel(Execution execution) {
        require(execution, ExecutionState.CREATED, ExecutionState.RUNNING, ExecutionState.SUSPENDED);
        execution.cancel();
    }

    public static void fail(Execution execution, String message) {
        require(execution, ExecutionState.CREATED, ExecutionState.RUNNING, ExecutionState.SUSPENDED);
        execution.fail(message);
    }

    private static void require(Execution execution, ExecutionState... allowed) {
        Objects.requireNonNull(execution, "execution");
        ExecutionState current = Objects.requireNonNull(execution.getExecutionState(), "execution.executionState");
        for (ExecutionState state : allowed) {
            if (current == state) {
                return;
            }
        }
        throw new IllegalStateException("Invalid execution transition from " + current + ": " + execution.getId());
    }
}
