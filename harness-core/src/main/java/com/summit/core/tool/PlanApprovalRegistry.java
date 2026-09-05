package com.summit.core.tool;

import java.io.Serializable;

/**
 * Registry of session plans awaiting human approval, indexed by the id of the
 * PLANNING execution that produced the plan.
 *
 * <p>Mirrors {@link CommandConfirmRegistry} for the plan-level approval point,
 * so both human-in-the-loop mechanisms share the same register / decide /
 * unregister contract. Implementations must be thread-safe, since it is used
 * concurrently by three threads:</p>
 * <ul>
 *   <li>agent loop thread: {@link #register} right after the plan is captured,
 *       {@link #unregister} once the approval finished;</li>
 *   <li>HTTP/host thread: {@link #decide} writes the approval decision;</li>
 *   <li>lifecycle / watchdog threads: {@link #get} / {@link #size} queries.</li>
 * </ul>
 */
public interface PlanApprovalRegistry {

    /**
     * Registers (or reuses) the gate for the given plan execution id.
     *
     * @return the existing or newly created gate; an execution id always maps to a single gate
     */
    PlanApprovalGate register(String planExecutionId, Serializable sessionId, String planTitle, String planText);

    PlanApprovalGate get(String planExecutionId);

    /**
     * Writes an approval decision.
     *
     * @return true if the decision was written; false if the gate is absent or already decided
     */
    boolean decide(String planExecutionId, CommandDecision decision);

    /** Removes the gate (approval finished / timed out / session ended). */
    void unregister(String planExecutionId);

    /** Number of plans currently awaiting approval. */
    int size();
}
