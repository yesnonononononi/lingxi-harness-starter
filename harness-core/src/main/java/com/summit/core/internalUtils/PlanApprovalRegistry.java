package com.summit.core.internalUtils;

import com.summit.core.tool.CommandDecision;

import java.io.Serializable;

/**
 * Registry of plans awaiting the human approval decision, indexed by plan id.
 *
 * <p>Thread-safe by contract: it is used concurrently by the agent-loop thread (register /
 * unregister), the HTTP thread (decide) and lifecycle threads (get / size).</p>
 */
public interface PlanApprovalRegistry {

    /**
     * Registers (or reuses) the gate of the given plan.
     *
     * @return the existing or newly created gate; a plan id always maps to a single gate
     */
    PlanApprovalGate register(String planId, Serializable sessionId, String executionId,
                              String planTitle, long planVersion, String planOutline);

    PlanApprovalGate get(String planId);

    /**
     * Writes an approval decision.
     *
     * @return true if the decision was written; false if the gate is absent or already decided
     */
    boolean decide(String planId, CommandDecision decision);

    /** Removes the gate (approval finished / timed out / session ended). */
    void unregister(String planId);

    /** Number of plans currently awaiting approval. */
    int size();
}
