package com.summit.core.tool;

public enum ConcurrentPolicy {
    SERIAL_MUTATION,     // 同工作区串行写
    READ_ONLY,           // 只读工具可并发
    ISOLATED_MUTATION    // 自带隔离的写操作可并发
}
