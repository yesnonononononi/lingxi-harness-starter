package com.summit.runtime.tool;

import com.summit.core.tool.CommandDecision;
import com.summit.core.tool.PlanApprovalGate;
import com.summit.core.tool.PlanApprovalRegistry;

import java.io.Serializable;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

/**
 * Default plan-approval registry: maps a plan execution id to its approval gate.
 * Mirrors {@link DefaultCommandConfirmRegistry} for the plan-level approval point.
 */
public class DefaultPlanApprovalRegistry implements PlanApprovalRegistry {

    private final ConcurrentMap<String, PlanApprovalGate> gates = new ConcurrentHashMap<>();

    @Override
    public PlanApprovalGate register(String planExecutionId, Serializable sessionId, String planTitle, String planText) {
        return gates.computeIfAbsent(planExecutionId,
                id -> new PlanApprovalGate(id, sessionId, planTitle, planText));
    }

    @Override
    public PlanApprovalGate get(String planExecutionId) {
        return gates.get(planExecutionId);
    }

    @Override
    public boolean decide(String planExecutionId, CommandDecision decision) {
        PlanApprovalGate gate = gates.get(planExecutionId);
        return gate != null && gate.decide(decision);
    }

    @Override
    public void unregister(String planExecutionId) {
        gates.remove(planExecutionId);
    }

    @Override
    public int size() {
        return gates.size();
    }
}
