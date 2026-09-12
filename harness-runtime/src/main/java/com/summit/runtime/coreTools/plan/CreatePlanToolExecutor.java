package com.summit.runtime.coreTools.plan;

import com.summit.core.internalUtils.plan.AbstractPlanToolExecutor;
import com.summit.core.internalUtils.plan.PlanToolNames;
import com.summit.core.internalUtils.plan.PlanToolRequests.CreatePlanRequest;
import com.summit.core.internalUtils.plan.PlanToolSupport;
import com.summit.core.plan.Plan;
import com.summit.core.plan.PlanOutline;
import com.summit.core.tool.ToolExecuteResult;
import com.summit.core.tool.ToolExecution;
import lombok.RequiredArgsConstructor;

/**
 * {@code create_plan}: registers the plan of the session as a draft and hands it over to the human
 * approval gate (which the agent loop opens right after this round).
 */
@RequiredArgsConstructor
public class CreatePlanToolExecutor extends AbstractPlanToolExecutor {

    private final PlanKernel kernel;

    @Override
    protected ToolExecuteResult doExecute(ToolExecution toolExecution) {
        CreatePlanRequest request = PlanToolSupport.parseCreate(toolExecution.getArgs());
        Plan plan = kernel.create(toolExecution.getSessionId(), toolExecution.getTurnId(), request);
        return PlanToolSupport.ok(toolExecution, """
                plan registered and now WAITING FOR THE USER'S APPROVAL (planId=%s, version=v%d, tasks=%d).
                Do not modify any file until the plan is approved: the runtime will resume you with the decision.
                If the user asks for changes, update the plan and it will be submitted for approval again.

                %s
                """.formatted(plan.id(), plan.version(), plan.tasks().size(), PlanOutline.render(plan)));
    }

    @Override
    protected String name() {
        return PlanToolNames.CREATE_PLAN;
    }
}
