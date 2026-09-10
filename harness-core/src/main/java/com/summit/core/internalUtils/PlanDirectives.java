package com.summit.core.internalUtils;

import com.summit.core.plan.Plan;
import com.summit.core.plan.PlanOutline;

/**
 * Text injected into the conversation at the three plan decision points (approved, revised, still
 * open). Replaces the old PLANING JSON contract: the model learns the plan contract from the tool
 * schema, and only the per-turn directives are still prose.
 */
public final class PlanDirectives {

    private PlanDirectives() {
    }

    /** Directive injected when the user approved the plan: implement it under the EXECUTE boundary. */
    public static String approvedPlanMessage(Plan plan) {
        return """
                The user APPROVED plan %s. Implement it now under the EXECUTE boundary.

                Rules:
                - the approved plan below is the authoritative revision: the user may have edited task
                  descriptions or acceptance criteria while reviewing it, so re-read it instead of
                  relying on the draft you wrote earlier;
                - work task by task; honour every task's acceptance criterion and dependencies;
                - keep the plan up to date with `update_task` (status=doing when you start a task);
                - call `complete_task` right after a task is really finished;
                - never stop while a task is still open.

                ## Approved plan
                %s
                """.formatted(plan.id(), PlanOutline.render(plan));
    }

    /** Directive injected when the user asked for a revision: produce a new plan at the PLANING boundary. */
    public static String revisedPlanMessage(Plan plan, String userFeedback) {
        String feedback = userFeedback == null || userFeedback.isBlank()
                ? "(no further comment was given)"
                : userFeedback.trim();
        return """
                The user reviewed plan %s and requests a revision. Call `update_plan` (or `create_plan`
                when the plan has to be rebuilt from scratch) so the plan addresses the feedback below.

                ## User feedback
                %s

                ## Current plan
                %s
                """.formatted(plan.id(), feedback, PlanOutline.render(plan));
    }

    /** Reminder injected when the agent tries to close while approved tasks are still open. */
    public static String openTasksReminder(Plan plan) {
        return """
                You tried to finish, but approved plan %s still has open tasks. Continue implementing
                them and call `complete_task` for each one as soon as it is really done.

                ## Open tasks
                %s
                """.formatted(plan.id(), PlanOutline.renderOpenTasks(plan));
    }
}
