package com.summit.core.plan;

/**
 * A single actionable step of a plan decision.
 *
 * @param id          unique step identifier within the plan
 * @param description human-readable description of the step
 * @param status      lifecycle status of the step
 */
public record PlanStep(
        String id,
        String description,
        PlanStepStatus status
) {
}
