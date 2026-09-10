package com.summit.core.plan;

import java.io.Serializable;
import java.util.Optional;

/**
 * Session-scoped plan store: one active plan per session, addressable by plan id as well.
 *
 * <p>The interface lives in harness-core so both harness-runtime (kernel tools, approval,
 * context rebuild) and plugins can share it without a cyclic module dependency. Implementations
 * (in-memory, Redis, ...) are pluggable through Spring {@code @ConditionalOnMissingBean}.</p>
 *
 * <p>Deliberately a plain persistence port: <b>no business rule lives here</b>. Version
 * arbitration, status transitions and event publication are owned by the runtime plan kernel,
 * which is the single write path.</p>
 */
public interface PlanStore {

    /**
     * Returns the plan with the given id, if any.
     *
     * @return the stored plan, or {@link Optional#empty()} when the id is unknown
     */
    Optional<Plan> findById(String planId);

    /**
     * Returns the active plan of the session, if any.
     *
     * @return the stored plan, or {@link Optional#empty()} when the session has no plan yet
     */
    Optional<Plan> findBySession(Serializable sessionId);

    /**
     * Stores the plan as the active plan of the session, replacing any previous plan.
     *
     * @return the stored plan
     */
    Plan save(Serializable sessionId, Plan plan);

    /**
     * Removes the active plan of the session.
     *
     * @return the removed plan, or {@link Optional#empty()} when nothing was stored
     */
    Optional<Plan> delete(Serializable sessionId);
}
