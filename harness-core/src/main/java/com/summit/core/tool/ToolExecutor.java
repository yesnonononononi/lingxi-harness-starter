package com.summit.core.tool;


import lombok.NonNull;

@FunctionalInterface
public interface ToolExecutor {
    @NonNull
    ToolExecuteResult execute(ToolExecution toolExecution);
}
