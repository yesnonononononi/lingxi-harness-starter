package com.summit.core.tool;

public enum ToolResultType {
    NORMAL,
    CONTEXT_COMPACT,
    /**
     * The tool call produced a committed placeholder result and requires the surrounding loop to
     * suspend after the complete tool batch has been collected.
     */
    PROMISE;
}
