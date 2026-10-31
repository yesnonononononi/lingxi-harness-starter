package com.summit.core.tool;

public enum ConcurrentPolicy {
    SERIAL,              // Never concurrent: the fail-safe default when no policy is declared
    SERIAL_MUTATION,     // Serial writes within the same workspace
    READ_ONLY,           // Read-only tools may run concurrently
    ISOLATED_MUTATION    // Self-isolated writes may run concurrently
}
