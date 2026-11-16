package com.summit.sandbox.windows;

import com.summit.core.workspace.WorkspaceSpec;

import java.util.Map;
import java.util.Objects;

/**
 * Workspace specification for the native Windows restricted-token sandbox provider.
 */
public record WindowsWorkspaceSpec(
        String workDir,
        String scope,
        Map<String, String> environment,
        boolean writable
) implements WorkspaceSpec {
    public WindowsWorkspaceSpec {
        if (workDir == null || workDir.isBlank()) {
            throw new IllegalArgumentException("workDir is required");
        }
        scope = scope == null ? "" : scope;
        environment = Map.copyOf(Objects.requireNonNull(environment, "environment"));
        for (String key : environment.keySet()) {
            if (!key.matches("[A-Za-z_][A-Za-z0-9_]*")) {
                throw new IllegalArgumentException("Invalid environment key: " + key);
            }
        }
    }

    public WindowsWorkspaceSpec(String workDir) {
        this(workDir, "", Map.of(), true);
    }

    public WindowsWorkspaceSpec(String workDir, boolean writable) {
        this(workDir, "", Map.of(), writable);
    }

    @Override
    public String provider() {
        return WindowsWorkspaceProvider.TYPE;
    }
}
