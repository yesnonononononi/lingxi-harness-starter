package com.summit.core.internalUtils.plan;

/**
 * Names of the plan kernel tools.
 *
 * <p>These five names are the whole plan API of the runtime. Four of them are exposed to the model
 * ({@code create_plan}, {@code update_plan}, {@code update_task}, {@code complete_task});
 * {@link #APPROVE_PLAN} is a user-side kernel command only — it is never registered as a model
 * tool, so the agent can never approve its own plan.</p>
 */
public final class PlanToolNames {

    public static final String CREATE_PLAN = "create_plan";
    public static final String UPDATE_PLAN = "update_plan";
    public static final String UPDATE_TASK = "update_task";
    public static final String COMPLETE_TASK = "complete_task";
    /** User-side approval command: not exposed to the model. */
    public static final String APPROVE_PLAN = "approve_plan";

    private PlanToolNames() {
    }
}
