package com.summit.core.runtime.loop;

import com.summit.core.agent.Execution;

/** Application reaction to a persisted failed execution. It cannot change the transition. */
@FunctionalInterface
public interface ExecutionFailureObserver {
    void onFailure(Execution execution, Exception cause);
}
