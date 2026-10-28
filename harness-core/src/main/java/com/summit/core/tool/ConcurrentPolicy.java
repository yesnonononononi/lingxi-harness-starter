package com.summit.core.tool;

public enum ConcurrentPolicy {
    SERIAL_MUTATION,     // Serial writes within the same workspace
    READ_ONLY,           // Read-only tools may run concurrently
    ISOLATED_MUTATION    // Self-isolated writes may run concurrently
}
