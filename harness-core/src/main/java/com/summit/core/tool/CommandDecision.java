package com.summit.core.tool;

/**
 * The decision a user makes at a human-in-the-loop approval point
 * (command-line tool approval, plan approval).
 */
public enum CommandDecision {
    APPROVE,
    REJECT,
    /** Plan approval only: the user wants the plan revised before it is implemented (back to PLANING). */
    REVISE;
}
