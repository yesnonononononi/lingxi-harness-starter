package com.summit.sandbox.windows;

import com.summit.core.workspace.WorkspaceAccessMode;

import java.util.Objects;

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
    READ_WRITE,

    /**
     * Allows reading and writing outside the designated workspace directory, however the execution of terminal tools is restricted by the sandbox
     */
    FULL_FILESYSTEM_ACCESS;

    public boolean writable() {
        return this != READ_ONLY;
    }

    public static SandboxMode mappingForWorkspace(WorkspaceAccessMode wam){
        return switch (Objects.requireNonNull(wam,"The workspace accessModel cannot be null")) {
            case WorkspaceAccessMode.READ_ONLY ->  SandboxMode.READ_ONLY;

            case WorkspaceAccessMode.READ_WRITE ->  SandboxMode.READ_WRITE;

            case WorkspaceAccessMode.FULL_FILESYSTEM_ACCESS -> SandboxMode.FULL_FILESYSTEM_ACCESS;
        };
    }
}
