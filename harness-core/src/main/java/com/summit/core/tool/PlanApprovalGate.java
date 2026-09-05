package com.summit.core.tool;

import lombok.Getter;

import java.io.Serializable;
import java.time.Instant;

/**
 * A "gate" for a single plan awaiting human approval before the agent starts
 * implementing it (PLANING boundary -&gt; user APPROVE/REJECT -&gt; EXECUTE).
 *
 * <p>Registered by the agent loop under the producing execution id right after
 * the plan text is captured; the loop then waits on {@link #awaitDecision}.
 * The approve/reject HTTP endpoints write the decision from any thread.</p>
 *
 * <p>The decision mechanics (pending / decide / await) are shared with the
 * command-approval gate through {@link AbstractApprovalGate}.</p>
 */
@Getter
public class PlanApprovalGate extends AbstractApprovalGate {

    /** Key of this gate: the id of the PLANNING execution that produced the plan. */
    private final String planExecutionId;
    private final Serializable sessionId;
    private final String planTitle;
    /** The raw plan text as produced by the agent (verbatim). */
    private final String planText;
    private final Instant createdAt = Instant.now();

    /** Optional user-supplied rejection reason; only meaningful after a REJECT decision. */
    private volatile String rejectReason;

    public PlanApprovalGate(String planExecutionId, Serializable sessionId, String planTitle, String planText) {
        this.planExecutionId = planExecutionId;
        this.sessionId = sessionId;
        this.planTitle = planTitle;
        this.planText = planText;
    }

    public void setRejectReason(String reason) {
        this.rejectReason = reason;
    }
}
