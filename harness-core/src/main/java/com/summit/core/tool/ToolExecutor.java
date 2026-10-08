package com.summit.core.tool;


import lombok.NonNull;

@FunctionalInterface
public interface ToolExecutor {
    @NonNull
    ToolExecuteResult execute(ToolExecution toolExecution);

    /** Workspace tools require a workspace; host-side resource readers may opt out. */
    default boolean requiresWorkspace() {
        return true;
    }
}
