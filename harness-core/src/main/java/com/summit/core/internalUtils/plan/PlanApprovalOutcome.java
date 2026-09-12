package com.summit.core.internalUtils.plan;

/**
 * Outcome of the human decision on a plan waiting for approval.
 */
public enum PlanApprovalOutcome {

    /** Approved: the agent implements the plan under the EXECUTE boundary. */
    APPROVED,

    /** The user asked for a revised plan: the agent goes back to the PLANING boundary. */
    REVISED,

    /** Rejected: the plan is never implemented; the execution ends normally. */
    REJECTED,

    /** No decision within the wait window: treated as a rejection. */
    TIMEOUT,

    /** The wait was interrupted (e.g. /stop): the execution must be cancelled. */
    INTERRUPTED
}
