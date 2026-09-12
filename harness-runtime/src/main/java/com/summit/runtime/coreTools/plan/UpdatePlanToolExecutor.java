package com.summit.runtime.coreTools.plan;

import com.summit.core.internalUtils.plan.AbstractPlanToolExecutor;
import com.summit.core.internalUtils.plan.PlanToolNames;
import com.summit.core.internalUtils.plan.PlanToolRequests.UpdatePlanRequest;
import com.summit.core.internalUtils.plan.PlanToolSupport;
import com.summit.core.plan.Plan;
import com.summit.core.plan.PlanOutline;
import com.summit.core.tool.ToolExecuteResult;
import com.summit.core.tool.ToolExecution;
import lombok.RequiredArgsConstructor;

/**
 * {@code update_plan}: structural batch update of an existing plan (add / update / remove tasks,
 * rewrite the plan meta) guarded by the optimistic version check.
 */
@RequiredArgsConstructor
public class UpdatePlanToolExecutor extends AbstractPlanToolExecutor {

    private final PlanKernel kernel;

    @Override
    protected ToolExecuteResult doExecute(ToolExecution toolExecution) {
        UpdatePlanRequest request = PlanToolSupport.parseUpdatePlan(toolExecution.getArgs());
        Plan plan = kernel.updatePlan(toolExecution.getSessionId(), request.planId(), request.version(),
                request.operations(), toolExecution.getTurnId());
        return PlanToolSupport.ok(toolExecution, """
                plan updated: planId=%s, version=v%d, %d/%d tasks done, %d operation(s) applied.

                %s
                """.formatted(plan.id(), plan.version(), plan.doneTaskCount(), plan.tasks().size(),
                request.operations().size(), PlanOutline.render(plan)));
    }

    @Override
    protected String name() {
        return PlanToolNames.UPDATE_PLAN;
    }
}
