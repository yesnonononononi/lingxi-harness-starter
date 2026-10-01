package com.summit.core.runtime.loop;

import com.summit.core.agent.Execution;

import java.util.Optional;

/**
 * Execution snapshots and the matching running execution's cooperative control signal.
 * Implementations may be process-local or backed by shared infrastructure.
 */
public interface ExecutionRepository extends ActiveExecutionRegistry {
    /** Store an isolated, recoverable snapshot at a committed loop boundary. */
    void save(Execution execution);

    Optional<Execution> findById(String executionId);

    /**
     * Run a notification only after the surrounding snapshot transaction commits.
     * Non-transactional repositories can use the immediate default.
     */
    default void afterCommit(Runnable notification) {
        notification.run();
    }
}
