package com.summit.core.internalUtils;

import com.summit.core.agent.Execution;
import com.summit.core.tool.ToolExecuteResult;

import java.io.Serializable;
import java.util.List;

/**
 * The <b>only</b> integration point between the generic agent loop and the plan mode.
 *
 * <p>Plan behaviour used to leak into the loop through a text parser, a plan coordinator and several
 * conversation-manager methods. With tool-driven plans the loop only asks three questions, and this
 * interface contains no plan-domain vocabulary beyond "the plan changed" — the loop merely applies
 * the returned {@link PlanTurnResult}: inject the directive, maybe switch boundary, maybe stop.</p>
 *
 * <p>Implementations live in the runtime ({@code com.summit.runtime.internalUtils}); the interface
 * lives in harness-core so {@code RuntimeContext} can carry it without a cyclic module dependency.</p>
 */
public interface PlanLoopHook {

    /** What the loop must do after consulting the hook. */
    enum PlanTurnAction {
        /** Nothing plan-related happened: apply the loop's default behaviour for this point. */
        NONE,
        /** Keep looping after injecting the returned directive. */
        CONTINUE,
        /** The plan was approved: switch to the EXECUTE boundary and keep looping. */
        SWITCH_TO_EXECUTE,
        /** The plan was rejected / the approval timed out: end the execution normally. */
        STOP,
        /** The approval wait was interrupted (e.g. /stop): cancel the execution. */
        CANCEL
    }

    /** No-op hook used when no plan kernel is wired (craft mode): the loop behaves exactly as before. */
    PlanLoopHook NOOP = new PlanLoopHook() {
        @Override
        public PlanTurnResult afterToolTurn(List<ToolExecuteResult> toolResults, Execution execution,
                                           Serializable sessionId) {
            return PlanTurnResult.none();
        }

        @Override
        public PlanTurnResult onPlainTextTurn(Execution execution, Serializable sessionId) {
            return PlanTurnResult.none();
        }

        @Override
        public void onExecutionFinished(Execution execution, Serializable sessionId,
                                        boolean executedWriteSuccessfully, boolean closedByPlainText) {
        }
    };

    /**
     * Handles the plan side effects of one tool round: detects a plan created by {@code create_plan},
     * waits for the human decision and — when approved — asks the loop to continue implementing it.
     *
     * @param toolResults results of the round just executed
     * @return what the loop should do next, plus the directive to inject
     */
    PlanTurnResult afterToolTurn(List<ToolExecuteResult> toolResults, Execution execution, Serializable sessionId);

    /**
     * A plain-text turn would normally close the execution. When an approved plan still has open
     * tasks, a reminder is injected and {@link PlanTurnAction#CONTINUE} is returned so the loop
     * keeps working instead of closing.
     *
     * @return {@link PlanTurnResult#none()} to let the loop close normally
     */
    PlanTurnResult onPlainTextTurn(Execution execution, Serializable sessionId);

    /**
     * Finalisation hook: closes the plan when the approved plan was actually implemented.
     */
    void onExecutionFinished(Execution execution, Serializable sessionId,
                             boolean executedWriteSuccessfully, boolean closedByPlainText);
}
