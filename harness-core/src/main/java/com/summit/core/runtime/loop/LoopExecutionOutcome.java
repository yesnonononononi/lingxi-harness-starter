package com.summit.core.runtime.loop;

/** Neutral facts about a finished loop; hooks decide their own domain meaning. */
public record LoopExecutionOutcome(boolean writeToolExecuted) {
}
