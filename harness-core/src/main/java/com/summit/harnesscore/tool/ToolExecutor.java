package com.summit.harnesscore.tool;

import org.jspecify.annotations.NonNull;

@FunctionalInterface
public interface ToolExecutor {
    @NonNull ToolExecuteResult execute(ToolExecution toolExecution);
}
