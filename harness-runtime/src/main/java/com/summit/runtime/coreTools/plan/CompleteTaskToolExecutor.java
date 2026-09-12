package com.summit.runtime.coreTools.plan;

import com.summit.core.internalUtils.plan.AbstractPlanToolExecutor;
import com.summit.core.internalUtils.plan.PlanArgumentException;
import com.summit.core.internalUtils.plan.PlanToolNames;
import com.summit.core.internalUtils.plan.PlanToolRequests.CompleteTaskRequest;
import com.summit.core.internalUtils.plan.PlanToolSupport;
import com.summit.core.plan.Plan;
import com.summit.core.plan.PlanOutline;
import com.summit.core.plan.PlanStatus;
import com.summit.core.plan.Task;
import com.summit.core.tool.ToolExecuteResult;
import com.summit.core.tool.ToolExecution;
import lombok.RequiredArgsConstructor;

/**
 * {@code complete_task}: closes one task and, when it was the last open one, closes the plan itself
 * so the agent knows it may write the final answer.
 */
@RequiredArgsConstructor
public class CompleteTaskToolExecutor extends AbstractPlanToolExecutor {

    private final PlanKernel kernel;

    @Override
    protected ToolExecuteResult doExecute(ToolExecution toolExecution) {
        CompleteTaskRequest request = PlanToolSupport.parseCompleteTask(toolExecution.getArgs());
        Plan plan = kernel.completeTask(toolExecution.getSessionId(), request.planId(), request.taskId(),
                request.version(), toolExecution.getTurnId());
        Task task = plan.taskOf(request.taskId())
                .orElseThrow(() -> new PlanArgumentException(PlanToolSupport.unknownTask(plan, request.taskId())));
        if (plan.status() == PlanStatus.DONE) {
            return PlanToolSupport.ok(toolExecution,
                    "task completed: [%s] %s. Every task of plan %s is now done (version=v%d): the plan is closed, "
                            .formatted(task.id(), task.title(), plan.id(), plan.version())
                            + "you may write the final answer.");
        }
        return PlanToolSupport.ok(toolExecution, """
                task completed: [%s] %s (planId=%s, plan version=v%d, %d/%d tasks done).

                ## Still open
                %s
                """.formatted(task.id(), task.title(), plan.id(), plan.version(),
                plan.doneTaskCount(), plan.tasks().size(), PlanOutline.renderOpenTasks(plan)));
    }

    @Override
    protected String name() {
        return PlanToolNames.COMPLETE_TASK;
    }
}
