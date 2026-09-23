package com.summit.runtime.loop.control;

import com.summit.core.agent.Execution;
import com.summit.core.agent.ExecutionState;
import com.summit.core.runtime.loop.ExecutionRepository;
import com.summit.core.runtime.loop.ExecutionControlSignal;

import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/** In-process registry of executions that are currently inside the agent loop. */
public final class InMemoryActiveExecutionRegistry implements ExecutionRepository {
    private final Map<String, ExecutionControlSignal> active = new ConcurrentHashMap<>();
    private final Map<String, Execution> snapshots = new ConcurrentHashMap<>();

    @Override
    public void save(Execution execution) {
        if (execution == null || execution.getId() == null || execution.getId().isBlank()) {
            throw new IllegalArgumentException("Execution.id is required");
        }
        ExecutionState state = execution.getExecutionState();
        if (state == ExecutionState.COMPLETED || state == ExecutionState.FAILED
                || state == ExecutionState.CANCELLED) {
            snapshots.remove(execution.getId());
        } else {
            snapshots.put(execution.getId(), execution);
        }
    }

    @Override
    public Optional<Execution> findById(String executionId) {
        return Optional.ofNullable(snapshots.get(executionId));
    }

    @Override
    public ExecutionControlSignal register(String executionId) {
        if (executionId == null || executionId.isBlank()) {
            throw new IllegalArgumentException("executionId is required");
        }
        ExecutionControlSignal signal = new ExecutionControlSignal(executionId);
        if (active.putIfAbsent(executionId, signal) != null) {
            throw new IllegalStateException("execution is already running: " + executionId);
        }
        return signal;
    }

    @Override
    public void unregister(ExecutionControlSignal signal) {
        if (signal == null || !active.remove(signal.getExecutionId(), signal)) {
            throw new IllegalArgumentException("execution control signal is not active");
        }
    }

    @Override
    public void requireSuspend(String executionId) {
        requireActive(executionId).requireSuspend();
    }

    @Override
    public void requireCancel(String executionId) {
        requireActive(executionId).requireCancel();
    }

    private ExecutionControlSignal requireActive(String executionId) {
        ExecutionControlSignal signal = active.get(executionId);
        if (signal == null) throw new IllegalStateException("execution is not running: " + executionId);
        return signal;
    }
}
