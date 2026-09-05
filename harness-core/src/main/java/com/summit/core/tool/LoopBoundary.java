package com.summit.core.tool;

import lombok.AllArgsConstructor;

@AllArgsConstructor
public enum LoopBoundary {
    PLANING("只读文件,给出实施计划/适配用户建议到计划"),
    EXECUTE("直接执行工作计划");
    public final String description;

    public static boolean allowExecute(LoopBoundary loopBoundary){
        // Backward compatibility: an absent boundary is treated as EXECUTE.
        if (loopBoundary == null) {
            return true;
        }
        return loopBoundary.equals(EXECUTE);
    }
}
