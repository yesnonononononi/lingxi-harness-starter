package com.summit.core.runtime;

/** A hook's product-neutral instruction to the loop. */
public record LoopTurnResult(Action action, String directive) {

    private static final LoopTurnResult NONE = new LoopTurnResult(Action.NONE, null);

    public static LoopTurnResult none() { return NONE; }
    public static LoopTurnResult continueWith(String directive) { return new LoopTurnResult(Action.CONTINUE, directive); }
    public static LoopTurnResult switchToExecute(String directive) { return new LoopTurnResult(Action.SWITCH_TO_EXECUTE, directive); }
    public static LoopTurnResult stop() { return new LoopTurnResult(Action.STOP, null); }
    public static LoopTurnResult cancel() { return new LoopTurnResult(Action.CANCEL, null); }

    public boolean hasDirective() { return directive != null && !directive.isBlank(); }

    public enum Action { NONE, CONTINUE, SWITCH_TO_EXECUTE, STOP, CANCEL }
}
