package com.summit.harnesscore.tool;
@FunctionalInterface
public interface ToolExecutor {
    ToolExecuteResult execute(ToolExecution toolExecution);
}
