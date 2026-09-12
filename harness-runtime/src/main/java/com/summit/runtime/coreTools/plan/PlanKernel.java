package com.summit.runtime.coreTools.plan;

import com.summit.core.conversation.event.PlanUpdateEvent;
import com.summit.core.conversation.event.RuntimeEventPublisher;
import com.summit.core.internalUtils.plan.PlanApprovalGate;
import com.summit.core.internalUtils.plan.PlanApprovalRegistry;
import com.summit.core.internalUtils.plan.PlanArgumentException;
import com.summit.core.internalUtils.plan.PlanToolRequests.CreatePlanRequest;
import com.summit.core.internalUtils.plan.PlanToolRequests.PlanOperation;
import com.summit.core.internalUtils.plan.PlanToolRequests.TaskPatch;
import com.summit.core.internalUtils.plan.PlanToolSupport;
import com.summit.core.plan.Plan;
import com.summit.core.plan.PlanStatus;
import com.summit.core.plan.PlanStore;
import com.summit.core.plan.Task;
import com.summit.core.plan.TaskStatus;
import com.summit.core.tool.CommandDecision;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

import java.io.Serializable;
import java.util.List;
import java.util.Optional;

/**
 * The single read/write path of the plan mode.
 *
 * <p>Every plan mutation — produced by the kernel tools, by the approval flow or by the front-end
 * edit endpoints — goes through this class, which owns the three things that must not be duplicated:
 * optimistic version arbitration, the plan/task state machine, and {@link PlanUpdateEvent}
 * publication. The {@code PlanStore} stays a dumb persistence port and the tool executors stay thin
 * adapters.</p>
 *
 * <p>Thread safety: the kernel itself is stateless; correctness relies on the store's atomic
 * {@code save} and the fact that plans are immutable records (copy-on-write).</p>
 */
@RequiredArgsConstructor
@Slf4j
public class PlanKernel {

    private static final String LOG_PREFIX = "【plan-kernel】";

    private final PlanStore planStore;
    /** Approval gates, used by the user-side approval commands. Optional. */
    private final PlanApprovalRegistry approvalRegistry;
    /** Event publisher used to push the plan card to the front-end. Optional. */
    private final RuntimeEventPublisher eventPublisher;

    // ------------------------------------------------------------------- create

    /**
     * Registers a brand-new draft plan for the session, replacing any previous plan of that session.
     */
    public Plan create(Serializable sessionId, String executionId, CreatePlanRequest request) {
        Plan previous = planStore.findBySession(sessionId).orElse(null);
        List<Task> tasks = request.tasks().stream().map(PlanToolSupport::taskFromDraft).toList();
        Plan plan = planStore.save(sessionId, Plan.create(request.title(), request.summaryMarkdown(), tasks));
        publish(sessionId, executionId, plan, PlanUpdateEvent.STATE_WAITING_APPROVAL);
        log.info("{} plan created: planId={}, sessionId={}, tasks={}, replacedPlan={}",
                LOG_PREFIX, plan.id(), sessionId, tasks.size(), previous == null ? null : previous.id());
        return plan;
    }

    // ---------------------------------------------------------------- mutations

    /** Applies the structural operations of {@code update_plan} after the version check. */
    public Plan updatePlan(Serializable sessionId, String planId, Long expectedVersion,
                           List<PlanOperation> operations, String executionId) {
        Plan plan = checkVersion(requireActivePlan(sessionId, planId), expectedVersion);
        Plan updated = PlanToolSupport.applyOperations(plan, operations);
        if (updated == plan) {
            return plan;
        }
        return persist(sessionId, executionId, updated, PlanUpdateEvent.STATE_UPDATED);
    }

    /** Applies a field-level patch to one task ({@code update_task}, and front-end task editing). */
    public Plan patchTask(Serializable sessionId, String planId, String taskId, TaskPatch patch,
                          Long expectedVersion, String executionId) {
        Plan plan = checkVersion(requireActivePlan(sessionId, planId), expectedVersion);
        requireTask(plan, taskId);
        Plan updated = plan.patchTask(taskId, task -> PlanToolSupport.applyPatch(task, patch));
        if (updated == plan) {
            return plan;
        }
        return persist(sessionId, executionId, updated, PlanUpdateEvent.STATE_UPDATED);
    }

    /**
     * Marks one task done ({@code complete_task}) and closes the plan as soon as every task is done.
     */
    public Plan completeTask(Serializable sessionId, String planId, String taskId, Long expectedVersion,
                             String executionId) {
        Plan plan = checkVersion(requireActivePlan(sessionId, planId), expectedVersion);
        Task task = requireTask(plan, taskId);
        if (task.isDone()) {
            return plan;
        }
        Plan updated = plan.patchTask(taskId, current -> current.withStatus(TaskStatus.DONE));
        if (updated.allTasksDone() && updated.status() != PlanStatus.DONE) {
            updated = updated.withStatus(PlanStatus.DONE);
        }
        String state = updated.status() == PlanStatus.DONE
                ? PlanUpdateEvent.STATE_DONE
                : PlanUpdateEvent.STATE_UPDATED;
        return persist(sessionId, executionId, updated, state);
    }

    /** Transitions the plan state machine and publishes the matching plan card state. */
    public Plan markStatus(Serializable sessionId, String executionId, Plan plan, PlanStatus status,
                           String state) {
        Plan updated = plan.status() == status ? plan : planStore.save(sessionId, plan.withStatus(status));
        publish(sessionId, executionId, updated, state);
        log.info("{} plan status {} -> {}: planId={}, sessionId={}",
                LOG_PREFIX, plan.status(), updated.status(), updated.id(), sessionId);
        return updated;
    }

    // ----------------------------------------------------------- approval commands

    /** Approves the waiting plan — the user-side {@code approve_plan} kernel command. */
    public boolean approve(String planId) {
        return decide(planId, CommandDecision.APPROVE, gate -> {
        });
    }

    /** Asks for a revised plan, carrying the user feedback to the waiting agent. */
    public boolean revise(String planId, String feedback) {
        return decide(planId, CommandDecision.REVISE, gate -> gate.setReviseMessage(feedback));
    }

    /** Rejects the waiting plan; it is never implemented. */
    public boolean reject(String planId, String reason) {
        return decide(planId, CommandDecision.REJECT, gate -> gate.setRejectReason(reason));
    }

    private boolean decide(String planId, CommandDecision decision, java.util.function.Consumer<PlanApprovalGate> enricher) {
        PlanApprovalGate gate = approvalRegistry == null ? null : approvalRegistry.get(planId);
        if (gate == null) {
            log.warn("{} no plan waiting for approval, {} ignored: planId={}", LOG_PREFIX, decision, planId);
            return false;
        }
        enricher.accept(gate);
        boolean decided = approvalRegistry.decide(planId, decision);
        log.info("{} plan decision {} written={}: planId={}, sessionId={}",
                LOG_PREFIX, decision, decided, planId, gate.getSessionId());
        return decided;
    }

    // ----------------------------------------------------------------- accessors

    /** The active plan of the session, if any. */
    public Optional<Plan> planOf(Serializable sessionId) {
        return planStore.findBySession(sessionId);
    }

    /** A plan by id, if any. */
    public Optional<Plan> planById(String planId) {
        return planStore.findById(planId);
    }

    /** Whether the session plan was approved and still owns tasks that are not done. */
    public boolean hasPendingWork(Serializable sessionId) {
        return planOf(sessionId)
                .filter(plan -> plan.status() == PlanStatus.APPROVED || plan.status() == PlanStatus.EXECUTING)
                .map(plan -> !plan.openTaskIds().isEmpty())
                .orElse(false);
    }

    /** Drops the plan of the session (e.g. session closed). */
    public Optional<Plan> delete(Serializable sessionId) {
        return planStore.delete(sessionId);
    }

    /** Publishes a plan card state to the front-end; never lets a listener failure break the kernel. */
    public void publish(Serializable sessionId, String executionId, Plan plan, String state) {
        if (eventPublisher == null || plan == null) {
            return;
        }
        try {
            eventPublisher.onPlanUpdate(new PlanUpdateEvent(sessionId, executionId, plan, state));
        } catch (Exception e) {
            log.warn("{} failed to publish plan update: planId={}, state={}, error={}",
                    LOG_PREFIX, plan.id(), state, e.getMessage());
        }
    }

    // ------------------------------------------------------------------- private

    private Plan persist(Serializable sessionId, String executionId, Plan updated, String state) {
        Plan saved = planStore.save(sessionId, updated);
        publish(sessionId, executionId, saved, state);
        log.info("{} plan updated: planId={}, version={}, status={}, tasks={}/{} done",
                LOG_PREFIX, saved.id(), saved.version(), saved.status(),
                saved.doneTaskCount(), saved.tasks().size());
        return saved;
    }

    /** Resolves the session's active plan, rejecting unknown / non-active plan ids for the model. */
    private Plan requireActivePlan(Serializable sessionId, String planId) {
        Plan plan = planStore.findBySession(sessionId)
                .orElseThrow(() -> new PlanArgumentException(PlanToolSupport.noPlanInSession()));
        if (planId != null && !plan.id().equals(planId)) {
            throw new PlanArgumentException(
                    "plan_id '%s' is not the active plan of this session; active plan is %s"
                            .formatted(planId, plan.id()));
        }
        return plan;
    }

    private Plan checkVersion(Plan plan, Long expectedVersion) {
        if (expectedVersion != null && expectedVersion != plan.version()) {
            throw new PlanArgumentException(PlanToolSupport.versionConflict(plan, expectedVersion));
        }
        return plan;
    }

    private Task requireTask(Plan plan, String taskId) {
        return plan.taskOf(taskId)
                .orElseThrow(() -> new PlanArgumentException(PlanToolSupport.unknownTask(plan, taskId)));
    }
}
