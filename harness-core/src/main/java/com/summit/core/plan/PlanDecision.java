package com.summit.core.plan;

import java.util.List;

/**
 * Structured plan produced by the agent during a PLANNING execution and returned
 * to the front-end for display (via {@code PlanDecisionEvent} / {@code Execution}).
 *
 * @param title plan title
 * @param steps ordered plan steps
 */
public record PlanDecision(
        String title,
        List<PlanStep> steps
) {
}
