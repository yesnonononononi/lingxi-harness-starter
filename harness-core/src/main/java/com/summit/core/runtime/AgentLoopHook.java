package com.summit.core.runtime;

import com.summit.core.agent.Execution;
import com.summit.core.tool.ToolExecuteResult;

import java.io.Serializable;
import java.util.List;

/**
 * Product-neutral extension points around an agent loop.
 * Applications may implement plans, workflows or policy engines without making the runtime know
 * their domain model.
 */
public interface AgentLoopHook {

    AgentLoopHook NOOP = new AgentLoopHook() {};

    default LoopTurnResult afterToolTurn(List<ToolExecuteResult> results, Execution execution,
                                         Serializable sessionId) {
        return LoopTurnResult.none();
    }

    default LoopTurnResult onPlainTextTurn(Execution execution, Serializable sessionId) {
        return LoopTurnResult.none();
    }

    default void onExecutionFinished(Execution execution, Serializable sessionId,
                                     LoopExecutionOutcome outcome) {
    }
}
