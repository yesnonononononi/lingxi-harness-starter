package com.summit.sandbox.mxc;

import com.summit.core.workspace.WorkspaceSpec;

import java.util.List;
import java.util.Map;
import java.util.Objects;

/** Explicit filesystem grants. Network access is always denied by this adapter. */
public record MxcWorkspaceSpec(String workDir,
                               String scope,
                               List<String> readonlyPaths,
                               List<String> readwritePaths,
                               Map<String, String> environment,
                               boolean writable
) implements WorkspaceSpec {
    public MxcWorkspaceSpec {
        if (workDir == null || workDir.isBlank()) throw new IllegalArgumentException("workDir is required");
        readonlyPaths = List.copyOf(Objects.requireNonNull(readonlyPaths, "readonlyPaths"));
        readwritePaths = List.copyOf(Objects.requireNonNull(readwritePaths, "readwritePaths"));
        environment = Map.copyOf(Objects.requireNonNull(environment, "environment"));
        for (String key : environment.keySet()) {
            if (!key.matches("[A-Za-z_][A-Za-z0-9_]*")) throw new IllegalArgumentException("Invalid environment key: " + key);
        }
    }

    public MxcWorkspaceSpec(String workDir) {
        this(workDir, "", List.of(), List.of(), Map.of(), true);
    }

    public MxcWorkspaceSpec(String workDir, String scope, List<String> readonlyPaths,
                            List<String> readwritePaths, Map<String, String> environment) {
        this(workDir, scope, readonlyPaths, readwritePaths, environment, true);
    }

    @Override public String provider() { return MxcWorkspaceProvider.TYPE; }
}
