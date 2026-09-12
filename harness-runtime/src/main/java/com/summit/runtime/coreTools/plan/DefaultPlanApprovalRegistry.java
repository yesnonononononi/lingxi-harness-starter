package com.summit.runtime.coreTools.plan;

import com.summit.core.internalUtils.plan.PlanApprovalGate;
import com.summit.core.internalUtils.plan.PlanApprovalRegistry;
import com.summit.core.tool.CommandDecision;

import java.io.Serializable;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

/**
 * Default plan-approval registry: maps a plan id to the gate of the human decision.
 * Mirrors {@code DefaultCommandConfirmRegistry} for the plan-level approval point.
 */
public class DefaultPlanApprovalRegistry implements PlanApprovalRegistry {

    private final ConcurrentMap<String, PlanApprovalGate> gates = new ConcurrentHashMap<>();

    @Override
    public PlanApprovalGate register(String planId, Serializable sessionId, String executionId,
                                     String planTitle, long planVersion, String planOutline) {
        return gates.computeIfAbsent(planId,
                id -> new PlanApprovalGate(id, sessionId, executionId, planTitle, planVersion, planOutline));
    }

    @Override
    public PlanApprovalGate get(String planId) {
        return planId == null ? null : gates.get(planId);
    }

    @Override
    public boolean decide(String planId, CommandDecision decision) {
        PlanApprovalGate gate = get(planId);
        return gate != null && gate.decide(decision);
    }

    @Override
    public void unregister(String planId) {
        if (planId != null) {
            gates.remove(planId);
        }
    }

    @Override
    public int size() {
        return gates.size();
    }
}
