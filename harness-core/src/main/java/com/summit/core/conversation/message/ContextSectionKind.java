package com.summit.core.conversation.message;

/** One renderable slice of the per-round dynamic context block. */
public enum ContextSectionKind {
    MCP,
    SKILL,
    MEMORY,
    WORK_DIR,
    PERMISSION,
    ENVIRONMENT,
    FORBIDDEN_TOOLS,
    TASK
}
