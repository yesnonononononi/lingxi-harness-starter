package com.summit.core.plan;

/**
 * Lifecycle status of a single plan step.
 * Newly produced plan steps start as {@link #PENDING}; {@link #IN_PROGRESS} and
 * {@link #COMPLETED} are reserved for execution-time progress tracking.
 */
public enum PlanStepStatus {
    PENDING,
    IN_PROGRESS,
    COMPLETED
}
