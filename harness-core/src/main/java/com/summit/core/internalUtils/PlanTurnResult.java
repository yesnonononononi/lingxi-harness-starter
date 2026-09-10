package com.summit.core.internalUtils;

/**
 * Answer of the plan hook to the agent loop: what to do next, plus an optional directive text the
 * loop appends to the conversation before continuing.
 *
 * <p>Keeping the directive in the result (instead of letting the hook touch the
 * {@code ConversationManager}) leaves the hook a pure decision component — no conversation, no
 * loop, easy to unit test.</p>
 *
 * @param action    what the loop must do next
 * @param directive text to append as a user message before the next model round; {@code null} when
 *                  nothing must be injected
 */
public record PlanTurnResult(PlanLoopHook.PlanTurnAction action, String directive) {

    private static final PlanTurnResult NONE = new PlanTurnResult(PlanLoopHook.PlanTurnAction.NONE, null);

    /** Carry on with the loop's default behaviour for this point. */
    public static PlanTurnResult none() {
        return NONE;
    }

    /** Keep looping after injecting {@code directive}. */
    public static PlanTurnResult keepGoing(String directive) {
        return new PlanTurnResult(PlanLoopHook.PlanTurnAction.CONTINUE, directive);
    }

    /** Switch to the EXECUTE boundary after injecting {@code directive}. */
    public static PlanTurnResult switchToExecute(String directive) {
        return new PlanTurnResult(PlanLoopHook.PlanTurnAction.SWITCH_TO_EXECUTE, directive);
    }

    /** End the execution normally (plan rejected / approval timed out). */
    public static PlanTurnResult stop() {
        return new PlanTurnResult(PlanLoopHook.PlanTurnAction.STOP, null);
    }

    /** Cancel the execution (approval wait interrupted, e.g. /stop). */
    public static PlanTurnResult cancel() {
        return new PlanTurnResult(PlanLoopHook.PlanTurnAction.CANCEL, null);
    }

    public boolean hasDirective() {
        return directive != null && !directive.isBlank();
    }
}
