package com.summit.core.plan;

/**
 * Plan-level state machine of the tool-driven plan mode.
 *
 * <pre>
 *   DRAFT --(human approves the plan)--> APPROVED --(agent starts executing)--> EXECUTING
 *   EXECUTING --(every task completed)--> DONE
 * </pre>
 *
 * <p>A freshly created plan is always {@link #DRAFT}: it must never be implemented before the
 * human approved it. Rejecting or timing out an approval leaves the plan in {@link #DRAFT}.</p>
 */
public enum PlanStatus {

    /** Created by {@code create_plan} and still waiting for the human decision. */
    DRAFT("草稿"),

    /** Approved by the user, the agent is about to implement it. */
    APPROVED("已批准"),

    /** The agent is implementing the approved plan. */
    EXECUTING("执行中"),

    /** Every task of the plan was completed. */
    DONE("已完成");

    private final String label;

    PlanStatus(String label) {
        this.label = label;
    }

    public String getLabel() {
        return label;
    }

    /** Tolerant parse used when a plan status arrives from JSON; falls back to {@link #DRAFT}. */
    public static PlanStatus parse(String raw) {
        if (raw == null || raw.isBlank()) {
            return DRAFT;
        }
        return switch (raw.trim().toLowerCase()) {
            case "approved" -> APPROVED;
            case "executing", "execution", "in_progress", "inprogress", "running" -> EXECUTING;
            case "done", "completed", "complete", "finished" -> DONE;
            default -> DRAFT;
        };
    }
}
