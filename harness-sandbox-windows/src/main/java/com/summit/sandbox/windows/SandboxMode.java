package com.summit.sandbox.windows;

/**
 * Permission tier enforced by the Windows restricted-token sandbox.
 */
public enum SandboxMode {
    /**
     * Allows reading inside the workspace and host runtime dependencies,
     * while blocking all filesystem writes everywhere.
     */
    READ_ONLY,
    /**
     * Allows reading and writing inside the designated workspace directory,
     * while blocking filesystem writes outside the workspace.
     */
    READ_WRITE
}
