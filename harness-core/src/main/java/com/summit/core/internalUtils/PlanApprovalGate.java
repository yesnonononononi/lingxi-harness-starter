package com.summit.core.internalUtils;

import com.summit.core.tool.AbstractApprovalGate;
import lombok.Getter;
import lombok.Setter;

import java.io.Serializable;
import java.time.Instant;

/**
 * Approval gate of one plan awaiting the human decision (DRAFT -&gt; approved / revised / rejected).
 *
 * <p>Keyed by <b>plan id</b>, since a tool-driven plan exists as a first-class entity: the front-end
 * card, the edit endpoints and the approval endpoints all address the same plan id, and a plan
 * keeps its identity across revisions.</p>
 *
 * <p>The decision mechanics (pending / decide / await) are shared with the command-approval gate
 * through {@link AbstractApprovalGate}.</p>
 */
@Getter
public class PlanApprovalGate extends AbstractApprovalGate {

    /** Plan this gate belongs to: also the registry key. */
    private final String planId;

    /** Session the plan belongs to. */
    private final Serializable sessionId;

    /** Execution that created the plan (carried on the SSE event). */
    private final String executionId;

    private final String planTitle;

    /** Plan revision the user is deciding on. */
    private final long planVersion;

    /** Rendered plan snapshot shown to the user while deciding. */
    private final String planOutline;

    private final Instant createdAt = Instant.now();

    /** Optional user-supplied rejection reason; only meaningful after a REJECT decision. */
    @Setter
    private volatile String rejectReason;

    /** Optional user feedback; only meaningful after a REVISE decision. */
    @Setter
    private volatile String reviseMessage;

    public PlanApprovalGate(String planId, Serializable sessionId, String executionId,
                            String planTitle, long planVersion, String planOutline) {
        this.planId = planId;
        this.sessionId = sessionId;
        this.executionId = executionId;
        this.planTitle = planTitle;
        this.planVersion = planVersion;
        this.planOutline = planOutline;
    }
}
