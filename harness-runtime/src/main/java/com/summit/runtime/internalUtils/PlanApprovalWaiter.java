package com.summit.runtime.internalUtils;

import com.summit.core.internalUtils.PlanApprovalGate;
import com.summit.core.internalUtils.PlanApprovalOutcome;
import com.summit.core.internalUtils.PlanApprovalRegistry;
import com.summit.core.plan.Plan;
import com.summit.core.plan.PlanOutline;
import com.summit.core.tool.CommandDecision;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

import java.io.Serializable;
import java.util.concurrent.TimeUnit;

/**
 * Waits for the human decision on a freshly created plan.
 *
 * <p>Same mechanism as command approval: register a gate under the plan id, wait with a bounded and
 * interruptible timeout, and let the approve / revise / reject endpoints write the decision from any
 * thread. Without a configured registry the plan is auto-approved, so a runtime that has no plan
 * approval UI still works.</p>
 */
@RequiredArgsConstructor
@Slf4j
public class PlanApprovalWaiter {

    private static final String LOG_PREFIX = "【plan-approval】";

    /** Plan-approval registry; plans are auto-approved when {@code null} (backward compatible). */
    private final PlanApprovalRegistry registry;

    /**
     * Longest wait for an approval decision; a timeout counts as rejection, so a session can never
     * pin the agent-loop thread forever. Configurable through
     * {@code lingxi.agent.runtime.tool.plan.approval-timeout-seconds}.
     */
    private final long timeoutSeconds;

    /**
     * The decision plus the optional user feedback carried by it (revision comments).
     *
     * @param outcome  what the user decided
     * @param feedback revision comments; only populated for {@link PlanApprovalOutcome#REVISED}
     */
    public record PlanApprovalResult(PlanApprovalOutcome outcome, String feedback) {

        static PlanApprovalResult of(PlanApprovalOutcome outcome) {
            return new PlanApprovalResult(outcome, null);
        }
    }

    /**
     * Suspends the calling (agent-loop) thread until the user decides on the plan.
     *
     * @return the decision; the gate is always removed before returning
     */
    public PlanApprovalResult await(Plan plan, Serializable sessionId, String executionId) {
        if (registry == null) {
            log.warn("{} no PlanApprovalRegistry configured, plan treated as auto-approved: planId={}",
                    LOG_PREFIX, plan.id());
            return PlanApprovalResult.of(PlanApprovalOutcome.APPROVED);
        }
        PlanApprovalGate gate = registry.register(plan.id(), sessionId, executionId, plan.title(),
                plan.version(), PlanOutline.render(plan));
        log.info("{} plan awaiting human approval: planId={}, sessionId={}, tasks={}",
                LOG_PREFIX, plan.id(), sessionId, plan.tasks().size());
        try {
            CommandDecision decision = gate.awaitDecision(timeoutSeconds, TimeUnit.SECONDS);
            if (decision == null) {
                log.warn("{} approval timed out after {}s, nothing was implemented: planId={}",
                        LOG_PREFIX, timeoutSeconds, plan.id());
                return PlanApprovalResult.of(PlanApprovalOutcome.TIMEOUT);
            }
            if (decision == CommandDecision.REJECT) {
                log.info("{} plan rejected by user, nothing was implemented: planId={}, reason={}",
                        LOG_PREFIX, plan.id(), gate.getRejectReason());
                return PlanApprovalResult.of(PlanApprovalOutcome.REJECTED);
            }
            if (decision == CommandDecision.REVISE) {
                log.info("{} plan revision requested: planId={}, hasFeedback={}",
                        LOG_PREFIX, plan.id(), gate.getReviseMessage() != null && !gate.getReviseMessage().isBlank());
                return new PlanApprovalResult(PlanApprovalOutcome.REVISED, gate.getReviseMessage());
            }
            log.info("{} plan approved by user: planId={}", LOG_PREFIX, plan.id());
            return PlanApprovalResult.of(PlanApprovalOutcome.APPROVED);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            log.warn("{} interrupted while awaiting approval: planId={}", LOG_PREFIX, plan.id());
            return PlanApprovalResult.of(PlanApprovalOutcome.INTERRUPTED);
        } finally {
            registry.unregister(plan.id());
        }
    }
}
