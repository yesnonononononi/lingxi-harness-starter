package com.summit.core.runtime.loop;

import com.summit.core.agent.Execution;

/** Observes the final outcome of an agent execution without controlling loop flow. */
public interface AgentLoopHook {

    AgentLoopHook NOOP = new AgentLoopHook() {};

    default void onExecutionFinished(Execution execution, LoopExecutionOutcome outcome) {
    }
}
