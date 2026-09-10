package com.summit.runtime.internalUtils;

import com.summit.core.agent.Execution;
import com.summit.core.conversation.event.PlanUpdateEvent;
import com.summit.core.internalUtils.PlanDirectives;
import com.summit.core.internalUtils.PlanLoopHook;
import com.summit.core.internalUtils.PlanTurnResult;
import com.summit.core.plan.Plan;
import com.summit.core.plan.PlanStatus;
import com.summit.core.tool.ToolExecuteResult;
import com.summit.core.tool.ToolResultType;
import com.summit.runtime.internalUtils.PlanApprovalWaiter.PlanApprovalResult;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

import java.io.Serializable;
import java.util.List;

/**
 * Default plan integration of the agent loop.
 *
 * <p>Responsibilities, all of them triggered from the three {@link PlanLoopHook} entry points:</p>
 * <ul>
 *   <li>a tool round created a draft plan =&gt; open the approval gate and wait for the user;
 *       approved plans switch the loop to the EXECUTE boundary, revisions come back with feedback;</li>
 *   <li>the model tries to close while approved tasks are open =&gt; refuse and remind it;</li>
 *   <li>the execution ended after writing =&gt; close the plan when every task is done.</li>
 * </ul>
 */
@RequiredArgsConstructor
@Slf4j
public class DefaultPlanLoopHook implements PlanLoopHook {

    private static final String LOG_PREFIX = "【plan-loop】";

    private final PlanKernel kernel;
    /** Approval waiter; it auto-approves when no registry is wired, so it is never {@code null}. */
    private final PlanApprovalWaiter waiter;

    @Override
    public PlanTurnResult afterToolTurn(List<ToolExecuteResult> toolResults, Execution execution,
                                        Serializable sessionId) {
        if (!carriesPlanUpdate(toolResults)) {
            return PlanTurnResult.none();
        }
        Plan plan = kernel.planOf(sessionId).orElse(null);
        if (plan == null) {
            return PlanTurnResult.none();
        }
        execution.setPlan(plan);
        if (plan.status() != PlanStatus.DRAFT) {
            // progress update of an already approved plan: nothing to decide
            return PlanTurnResult.none();
        }
        PlanApprovalResult decision = waiter.await(plan, sessionId, execution.getId());
        // The wait lasts until the human decides, and the plan card lets them edit task description /
        // acceptance in the meantime. Every branch therefore has to work on the revision that is
        // stored *now*: acting on the pre-wait snapshot would both hide those edits from the model
        // (the directive renders the plan it is given) and, for the approval path, write the stale
        // snapshot back over the user's revision.
        Plan decided = kernel.planOf(sessionId).orElse(plan);
        if (decided.version() != plan.version()) {
            log.info("{} plan edited while awaiting approval, continuing from the latest revision: "
                            + "planId={}, version=v{} -> v{}, executionId={}",
                    LOG_PREFIX, decided.id(), plan.version(), decided.version(), execution.getId());
        }
        execution.setPlan(decided);
        return switch (decision.outcome()) {
            case APPROVED -> onApproved(decided, execution, sessionId);
            case REVISED -> onRevised(decided, decision.feedback(), execution, sessionId);
            case INTERRUPTED -> {
                log.warn("{} approval interrupted, cancelling the execution: planId={}, executionId={}",
                        LOG_PREFIX, decided.id(), execution.getId());
                yield PlanTurnResult.cancel();
            }
            case REJECTED -> {
                kernel.publish(sessionId, execution.getId(), decided, PlanUpdateEvent.STATE_REJECTED);
                log.info("{} plan rejected, execution finishes without implementing it: planId={}, executionId={}",
                        LOG_PREFIX, decided.id(), execution.getId());
                yield PlanTurnResult.stop();
            }
            case TIMEOUT -> {
                kernel.publish(sessionId, execution.getId(), decided, PlanUpdateEvent.STATE_REJECTED);
                log.warn("{} plan approval timed out, execution finishes without implementing it: planId={}, executionId={}",
                        LOG_PREFIX, decided.id(), execution.getId());
                yield PlanTurnResult.stop();
            }
        };
    }

    @Override
    public PlanTurnResult onPlainTextTurn(Execution execution, Serializable sessionId) {
        if (!kernel.hasPendingWork(sessionId)) {
            return PlanTurnResult.none();
        }
        Plan plan = kernel.planOf(sessionId).orElse(null);
        if (plan == null) {
            return PlanTurnResult.none();
        }
        List<String> openTasks = plan.openTaskIds();
        log.warn("{} plain-text closure refused, {} plan task(s) still open, pushing the agent to continue: "
                        + "planId={}, executionId={}, openTasks={}",
                LOG_PREFIX, openTasks.size(), plan.id(), execution.getId(), openTasks);
        return PlanTurnResult.keepGoing(PlanDirectives.openTasksReminder(plan));
    }

    @Override
    public void onExecutionFinished(Execution execution, Serializable sessionId,
                                    boolean executedWriteSuccessfully, boolean closedByPlainText) {
        Plan plan = kernel.planOf(sessionId).orElse(null);
        if (plan == null) {
            return;
        }
        execution.setPlan(plan);
        if (!executedWriteSuccessfully || !closedByPlainText) {
            return;
        }
        if (plan.status() != PlanStatus.EXECUTING && plan.status() != PlanStatus.APPROVED) {
            return;
        }
        if (!plan.allTasksDone()) {
            log.warn("{} execution finished while {} plan task(s) are still open: planId={}, executionId={}",
                    LOG_PREFIX, plan.openTaskIds().size(), plan.id(), execution.getId());
            return;
        }
        kernel.markStatus(sessionId, execution.getId(), plan, PlanStatus.DONE, PlanUpdateEvent.STATE_DONE);
        log.info("{} plan implemented and closed: planId={}, executionId={}", LOG_PREFIX, plan.id(), execution.getId());
    }

    private PlanTurnResult onApproved(Plan plan, Execution execution, Serializable sessionId) {
        Plan executing = kernel.markStatus(sessionId, execution.getId(), plan, PlanStatus.EXECUTING,
                PlanUpdateEvent.STATE_APPROVED);
        execution.setPlan(executing);
        log.info("{} plan approved, switching to the EXECUTE boundary: planId={}, tasks={}, executionId={}",
                LOG_PREFIX, executing.id(), executing.tasks().size(), execution.getId());
        return PlanTurnResult.switchToExecute(PlanDirectives.approvedPlanMessage(executing));
    }

    private PlanTurnResult onRevised(Plan plan, String feedback, Execution execution, Serializable sessionId) {
        kernel.publish(sessionId, execution.getId(), plan, PlanUpdateEvent.STATE_REVISED);
        log.info("{} plan revision requested, staying at the PLANING boundary: planId={}, hasFeedback={}, executionId={}",
                LOG_PREFIX, plan.id(), feedback != null && !feedback.isBlank(), execution.getId());
        return PlanTurnResult.keepGoing(PlanDirectives.revisedPlanMessage(plan, feedback));
    }

    /** True when the round created or mutated a plan successfully. */
    private boolean carriesPlanUpdate(List<ToolExecuteResult> toolResults) {
        if (toolResults == null || toolResults.isEmpty()) {
            return false;
        }
        return toolResults.stream().anyMatch(result -> result != null
                && result.isSuccess()
                && result.getToolResultType() == ToolResultType.PLAN_UPDATED);
    }
}
