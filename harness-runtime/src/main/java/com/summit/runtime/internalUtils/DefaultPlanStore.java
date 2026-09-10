package com.summit.runtime.internalUtils;

import com.summit.core.plan.Plan;
import com.summit.core.plan.PlanStore;

import java.io.Serializable;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

/**
 * In-memory {@link PlanStore}: one active plan per session, addressable by plan id too.
 *
 * <p>Registered as the default bean so a persistent implementation (Redis, JDBC, ...) can take over
 * through {@code @ConditionalOnMissingBean}. Plans are immutable records, so every write is a plain
 * map put — concurrent readers (front-end, agent loop, edit endpoints) never see a half-updated
 * plan.</p>
 */
public class DefaultPlanStore implements PlanStore {

    /** sessionId -> planId of the session's active plan. */
    private final ConcurrentMap<Serializable, String> sessionIndex = new ConcurrentHashMap<>();

    /** planId -> plan snapshot. */
    private final ConcurrentMap<String, Plan> plans = new ConcurrentHashMap<>();

    @Override
    public Optional<Plan> findById(String planId) {
        return planId == null ? Optional.empty() : Optional.ofNullable(plans.get(planId));
    }

    @Override
    public Optional<Plan> findBySession(Serializable sessionId) {
        if (sessionId == null) {
            return Optional.empty();
        }
        String planId = sessionIndex.get(sessionId);
        return planId == null ? Optional.empty() : Optional.ofNullable(plans.get(planId));
    }

    @Override
    public Plan save(Serializable sessionId, Plan plan) {
        if (sessionId == null || plan == null) {
            throw new IllegalArgumentException("sessionId and plan are required to store a plan");
        }
        String previous = sessionIndex.put(sessionId, plan.id());
        plans.put(plan.id(), plan);
        if (previous != null && !previous.equals(plan.id())) {
            // the session replaced its plan (re-planning): drop the superseded snapshot
            plans.remove(previous);
        }
        return plan;
    }

    @Override
    public Optional<Plan> delete(Serializable sessionId) {
        if (sessionId == null) {
            return Optional.empty();
        }
        String planId = sessionIndex.remove(sessionId);
        return planId == null ? Optional.empty() : Optional.ofNullable(plans.remove(planId));
    }
}
