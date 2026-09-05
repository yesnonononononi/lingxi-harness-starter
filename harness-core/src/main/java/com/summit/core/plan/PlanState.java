package com.summit.core.plan;

/**
 * Plan-level state machine flag stored on {@link PlanEntity}, complementary to
 * the per-step lifecycle {@link PlanStepStatus}.
 *
 * <p>Deliberately <b>final-state oriented</b>: transitions happen only at the two
 * existing step-lifecycle call points (no per-tool / per-step churn), which keeps
 * the flag stable while still ending consistent:</p>
 * <pre>
 *   UN_APPROVED --(user approves -> steps IN_PROGRESS)--> APPROVED
 *   APPROVED    --(plan fully implemented -> steps COMPLETED)--> COMPLETED
 *
 *   reject / approval-timeout / interrupt: the flag simply stays where it is
 *   (UN_APPROVED when nothing was ever approved, APPROVED when an approved
 *   implementation was cut short) - it is never moved forward afterwards.
 * </pre>
 *
 * <p>Every {@link PlanEntity} is created as {@link #UN_APPROVED}: a freshly
 * produced plan must never be implemented before the human approves it.</p>
 */
public enum PlanState {

    /** Not approved: the plan exists but awaits / failed human approval; kept on reject, timeout or interrupt. */
    UN_APPROVED("未批准"),

    /** Approved: the human approved it and the agent is implementing the plan under the EXECUTE boundary. */
    APPROVED("已批准"),

    /** Completed: the approved plan actually ran write tools and closed normally (all steps COMPLETED). */
    COMPLETED("已完成");

    private final String label;

    PlanState(String label) {
        this.label = label;
    }

    public String getLabel() {
        return label;
    }
}
