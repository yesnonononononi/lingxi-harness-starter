package com.summit.core.tool;

public enum ToolResultType {
    NORMAL,
    CONTEXT_COMPACT,
    CONFIRM_REQUIRED,
    /**
     * The tool created or mutated a plan (plan kernel tools). The agent loop uses this type to
     * detect that the plan changed during the round — in particular that a fresh plan is waiting
     * for the human approval.
     */
    PLAN_UPDATED;
}
