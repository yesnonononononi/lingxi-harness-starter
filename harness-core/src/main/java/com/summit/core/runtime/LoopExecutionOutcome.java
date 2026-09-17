package com.summit.core.runtime;

/** Neutral facts about how a loop ended; hooks decide their own domain meaning. */
public record LoopExecutionOutcome(boolean writeToolExecuted, boolean closedByPlainText) {
}
