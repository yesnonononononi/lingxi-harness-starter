package com.summit.runtime;

import com.summit.core.agent.Execution;
import com.summit.core.tool.CommandDecision;
import com.summit.core.tool.PlanApprovalGate;
import com.summit.core.tool.PlanApprovalRegistry;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

import java.io.Serializable;
import java.util.concurrent.TimeUnit;

/**
 * Waits for a human decision on a captured plan. Same mechanism as command approval: register a gate
 * under the execution and wait with a bounded, interruptible timeout; the approve/reject HTTP endpoints
 * write the decision from any thread. Without a configured registry the plan is auto-approved.
 */
@RequiredArgsConstructor
@Slf4j
public class PlanApprovalWaiter {

    /** Longest wait for an approval decision; a timeout counts as rejection (so sessions cannot occupy the agent-loop thread forever). */
    private static final long TIMEOUT_SECONDS = 600L;

    /** Plan-approval registry; plans are auto-approved when {@code null} (backward compatible). */
    private final PlanApprovalRegistry registry;

    public enum PlanApprovalOutcome {
        /** Approved by the user: keep looping under the EXECUTE boundary and implement the plan. */
        APPROVED,
        /** Rejected by the user: end the execution without implementing the plan. */
        REJECTED,
        /** Timed out without a decision: treated as rejection. */
        TIMEOUT,
        /** Wait interrupted (e.g. /stop): the execution should be cancelled. */
        INTERRUPTED
    }

    public PlanApprovalOutcome await(Execution execution, Serializable sessionId,
                                     String planTitle, String planText) {
        if (registry == null) {
            log.warn("【agent-loop】no PlanApprovalRegistry configured, plan treated as auto-approved: executionId={}", execution.getId());
            return PlanApprovalOutcome.APPROVED;
        }
        PlanApprovalGate gate = registry.register(execution.getId(), sessionId, planTitle, planText);
        log.info("【agent-loop】plan captured, awaiting human approval: executionId={}", execution.getId());
        try {
            CommandDecision decision = gate.awaitDecision(TIMEOUT_SECONDS, TimeUnit.SECONDS);
            if (decision == null) {
                log.warn("【agent-loop】plan approval timed out after {}s: executionId={}, nothing was implemented",
                        TIMEOUT_SECONDS, execution.getId());
                return PlanApprovalOutcome.TIMEOUT;
            }
            if (decision == CommandDecision.REJECT) {
                log.info("【agent-loop】plan rejected by user: executionId={}, rejectReason={}, nothing was implemented",
                        execution.getId(), gate.getRejectReason());
                return PlanApprovalOutcome.REJECTED;
            }
            log.info("【agent-loop】plan approved by user: executionId={}", execution.getId());
            return PlanApprovalOutcome.APPROVED;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            log.warn("【agent-loop】interrupted while awaiting plan approval: executionId={}", execution.getId());
            return PlanApprovalOutcome.INTERRUPTED;
        } finally {
            registry.unregister(gate.getPlanExecutionId());
        }
    }
}
