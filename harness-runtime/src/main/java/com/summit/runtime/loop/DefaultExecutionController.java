package com.summit.runtime.loop;


import com.summit.core.agent.Agent;
import com.summit.core.agent.Execution;
import com.summit.core.agent.ExecutionState;
import com.summit.core.runtime.loop.ExecutionRepository;
import com.summit.core.runtime.loop.ExecutionControl;
import lombok.AllArgsConstructor;

import java.util.Objects;

@AllArgsConstructor
public class DefaultExecutionController implements ExecutionControl {
    private final Agent agent;
    private final ExecutionRepository executionRepository;


    @Override
    public void cancel(String executionId) {
        executionRepository.requireCancel(executionId);
    }

    @Override
    public void suspend(String executionId) {
        executionRepository.requireSuspend(executionId);
    }

    @Override
    public Execution resume(Execution execution) {
        Objects.requireNonNull(execution,"execution");
        Objects.requireNonNull(execution.getId(), "execution.id");
        Objects.requireNonNull(execution.getExecutionState(), "execution.executionState");
        if (execution.getExecutionState() != ExecutionState.SUSPENDED) {
            throw new IllegalStateException("Only a suspended execution can be resumed: " + execution.getId());
        }
        return agent.execute(execution);
    }

    @Override
    public Execution resume(String executionId) {
        Execution execution = executionRepository.findById(executionId)
                .orElseThrow(() -> new IllegalArgumentException("Execution not found: " + executionId));
        return resume(execution);
    }
}
