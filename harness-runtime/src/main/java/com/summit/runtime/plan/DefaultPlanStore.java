package com.summit.runtime.plan;

import com.summit.core.plan.PlanEntity;
import com.summit.core.plan.PlanState;
import com.summit.core.plan.PlanStepStatus;
import com.summit.core.plan.PlanStore;

import java.io.Serializable;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/**
 * In-memory {@link PlanStore} implementation (one plan per session, upsert by sessionId).
 * Registered as the default Spring bean so consuming modules can plug in a Redis-backed
 * implementation via {@code @ConditionalOnMissingBean}.
 */
public class DefaultPlanStore implements PlanStore {

    private final ConcurrentHashMap<Serializable, PlanEntity> store = new ConcurrentHashMap<>();

    @Override
    public Optional<PlanEntity> findBySession(Serializable sessionId) {
        return Optional.ofNullable(sessionId == null ? null : store.get(sessionId));
    }

    @Override
    public PlanEntity save(PlanEntity plan) {
        store.put(plan.sessionId(), plan);
        return plan;
    }

    @Override
    public Optional<PlanEntity> appendStep(Serializable sessionId, String description) {
        PlanEntity current = sessionId == null ? null : store.get(sessionId);
        if (current == null) {
            return Optional.empty();
        }
        PlanEntity updated = current.appendStep(description);
        store.put(sessionId, updated);
        return Optional.of(updated);
    }

    @Override
    public Optional<PlanEntity> markSteps(Serializable sessionId, PlanStepStatus status) {
        PlanEntity current = sessionId == null ? null : store.get(sessionId);
        if (current == null) {
            return Optional.empty();
        }
        PlanEntity updated = current.withStepsStatus(status);
        // Plan-level state machine is advanced on these two existing step-lifecycle
        // transitions, with no extra write calls (final-consistent, low churn):
        //   IN_PROGRESS -> steps start implementing  => plan was approved
        //   COMPLETED   -> steps all done            => approved plan was implemented
        if (status == PlanStepStatus.IN_PROGRESS && current.state() == PlanState.UN_APPROVED) {
            updated = updated.withState(PlanState.APPROVED);
        } else if (status == PlanStepStatus.COMPLETED && current.state() != PlanState.COMPLETED) {
            updated = updated.withState(PlanState.COMPLETED);
        }
        store.put(sessionId, updated);
        return Optional.of(updated);
    }

    @Override
    public Optional<PlanEntity> delete(Serializable sessionId) {
        return sessionId == null ? Optional.empty() : Optional.ofNullable(store.remove(sessionId));
    }
}
