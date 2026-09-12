package com.summit.runtime.coreTools.plan;

import com.summit.core.internalUtils.plan.AbstractPlanToolExecutor;
import com.summit.core.internalUtils.plan.PlanArgumentException;
import com.summit.core.internalUtils.plan.PlanToolNames;
import com.summit.core.internalUtils.plan.PlanToolRequests.UpdateTaskRequest;
import com.summit.core.internalUtils.plan.PlanToolSupport;
import com.summit.core.plan.Plan;
import com.summit.core.plan.PlanOutline;
import com.summit.core.plan.Task;
import com.summit.core.tool.ToolExecuteResult;
import com.summit.core.tool.ToolExecution;
import lombok.RequiredArgsConstructor;

/**
 * {@code update_task}: field-level patch of a single task (status, acceptance, description,
 * dependencies, priority, title).
 */
@RequiredArgsConstructor
public class UpdateTaskToolExecutor extends AbstractPlanToolExecutor {

    private final PlanKernel kernel;

    @Override
    protected ToolExecuteResult doExecute(ToolExecution toolExecution) {
        UpdateTaskRequest request = PlanToolSupport.parseUpdateTask(toolExecution.getArgs());
        Plan plan = kernel.patchTask(toolExecution.getSessionId(), request.planId(), request.taskId(),
                request.patch(), request.version(), toolExecution.getTurnId());
        Task task = plan.taskOf(request.taskId())
                .orElseThrow(() -> new PlanArgumentException(PlanToolSupport.unknownTask(plan, request.taskId())));
        return PlanToolSupport.ok(toolExecution, """
                task updated: [%s] %s (status=%s, planId=%s, plan version=v%d, %d/%d tasks done).

                %s
                """.formatted(task.id(), task.title(), task.status().name().toLowerCase(), plan.id(),
                plan.version(), plan.doneTaskCount(), plan.tasks().size(), PlanOutline.renderOpenTasks(plan)));
    }

    @Override
    protected String name() {
        return PlanToolNames.UPDATE_TASK;
    }
}
