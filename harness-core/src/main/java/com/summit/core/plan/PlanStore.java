package com.summit.core.plan;

import java.io.Serializable;
import java.util.Optional;

/**
 * Session-scoped plan store (one plan per session).
 *
 * <p>The interface lives in harness-core so both harness-runtime (conversation /
 * context rebuild) and harness-base-tools (compact_context executor) can share it
 * without a cyclic module dependency. Implementations (in-memory, Redis, ...) live
 * in the consuming modules and are pluggable via Spring {@code @ConditionalOnMissingBean}.</p>
 */
public interface PlanStore {

    /**
     * Returns the plan registered for the given session, if any.
     *
     * @return the plan entity, or {@link Optional#empty()} when the session has no plan yet
     */
    Optional<PlanEntity> findBySession(Serializable sessionId);

    /**
     * Stores the plan under its {@code sessionId}, replacing any previous plan of the session.
     *
     * @return the stored plan
     */
    PlanEntity save(PlanEntity plan);

    /**
     * Appends a new PENDING implementation step to the plan of the given session.
     *
     * @return the updated plan, or {@link Optional#empty()} when the session has no plan
     */
    Optional<PlanEntity> appendStep(Serializable sessionId, String description);

    /**
     * Transitions every step of the session plan to the given status (e.g. all steps to
     * {@code IN_PROGRESS} when an execution starts implementing the plan, or to
     * {@code COMPLETED} when the execution finishes normally after actually running tools).
     * Implementations should <b>also advance the plan-level {@link PlanState} machine</b>
     * on these same transitions (IN_PROGRESS -&gt; {@code APPROVED}, COMPLETED -&gt;
     * {@code COMPLETED}) instead of requiring separate write calls, keeping the state
     * machine final-consistent with minimal churn. A no-op when the session has no plan.
     *
     * @return the updated plan, or {@link Optional#empty()} when the session has no plan
     */
    Optional<PlanEntity> markSteps(Serializable sessionId, PlanStepStatus status);

    /**
     * Removes the plan of the given session.
     *
     * @return the removed plan, or {@link Optional#empty()} when nothing was stored
     */
    Optional<PlanEntity> delete(Serializable sessionId);
}
