package com.summit.harnessexample;

import com.summit.core.tool.CommandDecision;
import com.summit.harnessexample.common.Result;
import com.summit.harnessexample.dto.PlanDecisionRequest;
import com.summit.harnessexample.dto.TaskUpdateRequest;
import com.summit.harnessexample.service.PlanService;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

/**
 * Plan-mode endpoints consumed by the plan card: fetch a plan, decide it
 * (approve / revise / reject) and edit a task before approving.
 */
@RestController
@RequestMapping("/agent")
@RequiredArgsConstructor
public class PlanController {

    private final PlanService planService;

    /** A plan by id, so the front-end can render / refresh the plan card. */
    @GetMapping("/plans/{planId}")
    public Result<Map<String, Object>> planById(@PathVariable("planId") String planId) {
        return Result.ok(planService.byId(planId));
    }

    /** The active plan of a session, so the card can be restored after a reload. */
    @GetMapping("/sessions/{sessionId}/plan")
    public Result<Map<String, Object>> sessionPlan(@PathVariable("sessionId") String sessionId) {
        return Result.ok(planService.ofSession(sessionId));
    }

    /**
     * Approves the waiting plan — the user-side counterpart of the {@code approve_plan}
     * kernel command, which is deliberately never exposed to the model.
     */
    @PostMapping("/plans/{planId}/approve")
    public Result<Map<String, Object>> approve(@PathVariable("planId") String planId) {
        return Result.ok("plan decision recorded: APPROVE, agent loop will be woken up",
                planService.decide(planId, CommandDecision.APPROVE, null));
    }

    /** Requests a revised plan, carrying the user's feedback to the waiting agent. */
    @PostMapping("/plans/{planId}/revise")
    public Result<Map<String, Object>> revise(@PathVariable("planId") String planId,
            @RequestBody(required = false) PlanDecisionRequest request) {
        return Result.ok("plan decision recorded: REVISE, agent loop will be woken up",
                planService.decide(planId, CommandDecision.REVISE, request == null ? null : request.message()));
    }

    /** Rejects the waiting plan; it is never implemented. */
    @PostMapping("/plans/{planId}/reject")
    public Result<Map<String, Object>> reject(@PathVariable("planId") String planId,
            @RequestBody(required = false) PlanDecisionRequest request) {
        return Result.ok("plan decision recorded: REJECT, agent loop will be woken up",
                planService.decide(planId, CommandDecision.REJECT, request == null ? null : request.reason()));
    }

    /**
     * Field-level edit of one task while the plan awaits approval; the body carries the
     * rendered plan {@code version} so a stale edit is rejected with 409 + the latest plan.
     */
    @PutMapping("/sessions/{sessionId}/plans/{planId}/tasks/{taskId}")
    public Result<Map<String, Object>> updateTask(@PathVariable("sessionId") String sessionId,
            @PathVariable("planId") String planId,
            @PathVariable("taskId") String taskId,
            @RequestBody TaskUpdateRequest request) {
        return Result.ok(planService.patchTask(sessionId, planId, taskId, request));
    }
}
