package com.summit.harnessexample.service;

import com.summit.core.internalUtils.PlanApprovalGate;
import com.summit.core.internalUtils.PlanApprovalRegistry;
import com.summit.core.internalUtils.PlanArgumentException;
import com.summit.core.internalUtils.PlanToolRequests.TaskPatch;
import com.summit.core.plan.Plan;
import com.summit.core.plan.PlanOutline;
import com.summit.core.plan.Task;
import com.summit.core.tool.CommandDecision;
import com.summit.harnessexample.common.ApiException;
import com.summit.harnessexample.dto.TaskUpdateRequest;
import com.summit.runtime.internalUtils.PlanKernel;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Query + decision surface of the plan mode consumed by the front-end.
 *
 * <p>It never mutates a plan itself: reads go through {@link PlanKernel} and writes are
 * delegated to the kernel's single write path ({@code approve} / {@code revise} /
 * {@code reject} / {@code patchTask}), which owns optimistic version arbitration and the
 * {@code PLAN_UPDATE} broadcast.</p>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class PlanService {

    private final PlanKernel planKernel;
    private final PlanApprovalRegistry planApprovalRegistry;

    // ------------------------------------------------------------------- queries

    /** A plan by id, or 404 when unknown. */
    public Map<String, Object> byId(String planId) {
        return planKernel.planById(planId)
                .map(this::toData)
                .orElseThrow(() -> ApiException.notFound("no plan found for planId: " + planId,
                        Map.of("planId", planId)));
    }

    /** The active plan of a session, or 404 when the session has no plan yet. */
    public Map<String, Object> ofSession(String sessionId) {
        return planKernel.planOf(sessionId)
                .map(this::toData)
                .orElseThrow(() -> ApiException.notFound("no plan found for sessionId: " + sessionId,
                        Map.of("sessionId", sessionId)));
    }

    // ----------------------------------------------------------------- decisions

    /**
     * Writes the human decision on a plan waiting for approval and wakes the agent loop.
     *
     * @param feedback revision comment (REVISE) or rejection reason (REJECT); ignored for APPROVE
     */
    public Map<String, Object> decide(String planId, CommandDecision decision, String feedback) {
        PlanApprovalGate gate = planApprovalRegistry.get(planId);
        if (gate == null) {
            throw ApiException.notFound("no plan is waiting for approval with planId: " + planId,
                    Map.of("planId", planId, "pendingPlans", planApprovalRegistry.size()));
        }
        if (!gate.isPending()) {
            throw ApiException.conflict("plan already decided: " + gate.getDecision(),
                    Map.of("planId", planId,
                            "title", String.valueOf(gate.getPlanTitle()),
                            "decision", String.valueOf(gate.getDecision())));
        }

        boolean applied = switch (decision) {
            case APPROVE -> planKernel.approve(planId);
            case REVISE -> planKernel.revise(planId, feedback);
            case REJECT -> planKernel.reject(planId, feedback);
        };
        if (!applied) {
            throw ApiException.conflict("plan decision failed, it may have already been decided",
                    Map.of("planId", planId));
        }

        Map<String, Object> data = new LinkedHashMap<>();
        data.put("planId", planId);
        data.put("title", String.valueOf(gate.getPlanTitle()));
        data.put("version", gate.getPlanVersion());
        data.put("decision", decision.name());
        data.put("rejectReason", decision == CommandDecision.REJECT ? gate.getRejectReason() : null);
        data.put("reviseMessage", decision == CommandDecision.REVISE ? gate.getReviseMessage() : null);
        data.put("pendingPlans", planApprovalRegistry.size());
        log.info("【plan-decision】{} plan: planId={}, sessionId={}, version=v{}",
                decision, planId, gate.getSessionId(), gate.getPlanVersion());
        return data;
    }

    /**
     * Field-level edit of one task while the plan awaits approval. The body carries the
     * plan {@code version} the card rendered, so a stale edit is rejected with 409 plus
     * the latest plan instead of silently overwriting a newer revision.
     */
    public Map<String, Object> patchTask(String sessionId, String planId, String taskId, TaskUpdateRequest body) {
        TaskPatch patch = new TaskPatch(
                body.title(),
                body.description(),
                body.status(),
                body.dependencies(),
                body.priority(),
                body.acceptance());
        if (patch.isEmpty()) {
            throw ApiException.badRequest(
                    "patch must carry at least one of title, description, acceptance, status, dependencies, priority");
        }
        try {
            Plan updated = planKernel.patchTask(sessionId, planId, taskId, patch, body.version(), null);
            return toData(updated);
        } catch (PlanArgumentException e) {
            Map<String, Object> details = new LinkedHashMap<>();
            details.put("sessionId", sessionId);
            details.put("planId", planId);
            planKernel.planById(planId).ifPresent(latest -> details.put("latest", toData(latest)));
            throw ApiException.conflict(e.getMessage(), details);
        }
    }

    // ------------------------------------------------------------------ mapping

    /**
     * Serialises a plan for the front-end: meta + rendered document + ordered task list +
     * the approve / revise / reject addresses, so a client that fetched the plan once can
     * still drive the decision without building any URL itself.
     */
    public Map<String, Object> toData(Plan plan) {
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("id", plan.id());
        data.put("version", plan.version());
        data.put("title", plan.title());
        data.put("summaryMarkdown", plan.summaryMarkdown());
        data.put("outline", PlanOutline.render(plan));
        data.put("status", plan.status().name());
        data.put("statusLabel", plan.status().getLabel());
        data.put("doneTasks", plan.doneTaskCount());
        data.put("totalTasks", plan.tasks().size());
        data.put("progress", PlanOutline.progress(plan));
        data.put("createdAt", plan.createdAt().toString());
        data.put("updatedAt", plan.updatedAt().toString());
        data.put("tasks", plan.tasks().stream().map(this::taskData).toList());
        data.put("approveUrl", "/agent/plans/" + plan.id() + "/approve");
        data.put("reviseUrl", "/agent/plans/" + plan.id() + "/revise");
        data.put("rejectUrl", "/agent/plans/" + plan.id() + "/reject");
        return data;
    }

    private Map<String, Object> taskData(Task task) {
        Map<String, Object> item = new LinkedHashMap<>();
        item.put("id", task.id());
        item.put("title", task.title());
        item.put("description", task.description());
        item.put("status", task.status().name());
        item.put("statusLabel", task.status().getLabel());
        item.put("dependencies", task.dependencies());
        item.put("priority", task.priority());
        item.put("acceptance", task.acceptance());
        return item;
    }
}
