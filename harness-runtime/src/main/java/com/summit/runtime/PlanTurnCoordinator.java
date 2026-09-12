package com.summit.runtime;

import com.summit.core.agent.AgentRequest;
import com.summit.core.agent.Execution;
import com.summit.core.conversation.context.RuntimeContext;
import com.summit.core.internalUtils.plan.PlanLoopHook;
import com.summit.core.internalUtils.plan.PlanLoopHook.PlanTurnAction;
import com.summit.core.internalUtils.plan.PlanTurnResult;
import com.summit.core.tool.LoopBoundary;
import com.summit.core.tool.ToolExecuteResult;
import com.summit.runtime.agent.AgentLoopRunner;
import lombok.Getter;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

import java.io.Serializable;
import java.util.List;

/**
 * Plan-mode side of the loop: consults the {@link PlanLoopHook} and applies whatever it returns —
 * inject the directive, switch to the EXECUTE boundary, stop or cancel the execution.
 *
 * <p>Confining the hook here is what keeps {@link AgentLoopRunner} free of plan vocabulary: the loop
 * only asks "may I keep going?" and "did the plan stop me?", and never touches a plan itself.</p>
 */
@RequiredArgsConstructor
@Slf4j
public class PlanTurnCoordinator {

    /** How the loop must proceed after a plan consultation. */
    public enum Decision {
        /** Keep looping: a directive was injected and/or the boundary was switched. */
        CONTINUE,
        /** End this execution; the plan lifecycle had nothing to say, so it closes naturally. */
        CLOSE_NATURALLY,
        /** End this execution because the plan lifecycle stopped or cancelled it. */
        STOP
    }

    private final RuntimeContext context;

    /** True once a plan was approved: the rest of the run is forced onto the EXECUTE boundary.
     * -- GETTER --
     * True once the plan was approved; the loop must then run under the EXECUTE boundary.
     */
    @Getter
    private boolean planApproved;
    /** True once the plan lifecycle asked this execution to end.
     * -- GETTER --
     * True once the plan lifecycle asked for this execution to end.
     */
    @Getter
    private boolean stopped;

    /**
     * Plain-text turn: a round without tool calls would normally close the execution, but an approved
     * plan with open tasks makes the hook inject a reminder and ask the loop to continue.
     */
    public Decision onPlainTextTurn(Execution execution, Serializable sessionId) {
        PlanTurnAction action = apply(execution, sessionId, hook().onPlainTextTurn(execution, sessionId));
        if (action == PlanTurnAction.CONTINUE || action == PlanTurnAction.SWITCH_TO_EXECUTE) {
            return Decision.CONTINUE;
        }
        return action == PlanTurnAction.NONE ? Decision.CLOSE_NATURALLY : Decision.STOP;
    }

    /**
     * Tool-call turn: consulted after the round is persisted, so an injected directive lands after
     * this round's tool results.
     */
    public void afterToolTurn(Execution execution, Serializable sessionId, List<ToolExecuteResult> toolResults) {
        apply(execution, sessionId, hook().afterToolTurn(toolResults, execution, sessionId));
    }

    /**
     * Applies the decision of the hook: injects the directive it produced, refreshes the loop
     * boundary when the plan was approved, and records a stop / cancel request for the main loop.
     *
     * @return the action the hook decided
     */
    private PlanTurnAction apply(Execution execution, Serializable sessionId, PlanTurnResult turn) {
        PlanTurnAction action = turn == null ? PlanTurnAction.NONE : turn.action();
        if (turn != null && turn.hasDirective()) {
            context.getConversationManager().appendUserMessage(sessionId, turn.directive());
        }
        if (action == PlanTurnAction.SWITCH_TO_EXECUTE) {
            AgentRequest request = execution.getAgentRequest();
            context.getConversationManager().refreshBoundary(sessionId, LoopBoundary.EXECUTE,
                    request == null ? null : request.getSystemPrompt());
            planApproved = true;
            log.info("【agent-loop】plan approved, implementing under EXECUTE boundary: executionId={}", execution.getId());
        } else if (action == PlanTurnAction.CANCEL) {
            stopped = true;
            execution.cancel();
            log.warn("【agent-loop】plan approval interrupted, execution cancelled: {}", execution.getId());
        } else if (action == PlanTurnAction.STOP) {
            stopped = true;
            log.info("【agent-loop】plan not approved, execution finishes without implementing it: {}", execution.getId());
        }
        return action;
    }

    /** The plan hook of this runtime, or the no-op hook when no plan kernel is wired (craft mode). */
    private PlanLoopHook hook() {
        PlanLoopHook hook = context.getPlanLoopHook();
        return hook == null ? PlanLoopHook.NOOP : hook;
    }
}
