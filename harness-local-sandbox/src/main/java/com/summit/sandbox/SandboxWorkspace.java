package com.summit.sandbox;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.summit.core.runtime.RuntimeEnvironment;
import com.summit.core.runtime.workspace.Workspace;
import lombok.Builder;

import java.nio.file.Path;
import java.util.Objects;

/** Immutable execution environment returned by {@link SandboxWorkspaceProvider}. */
public final class SandboxWorkspace implements Workspace {
    private final String id;
    private final Path root;
    private final RuntimeEnvironment environment;
    private final SandboxWorkspaceBridge bridge;

    @Builder
    public SandboxWorkspace(String id, String workDir, RuntimeEnvironment runtimeEnvironment,
                            ObjectMapper objectMapper, CommandTimeoutConfig timeout, Path srtCli, Path nodeExe,
                            Path settingsFile, Path requestDirectory, Path applicationDirectory) {
        if (id == null || id.isBlank()) throw new IllegalArgumentException("workspace id must not be blank");
        if (workDir == null || workDir.isBlank()) throw new IllegalArgumentException("workspace workDir must not be blank");
        this.id = id;
        this.root = Path.of(workDir).toAbsolutePath().normalize();
        this.environment = Objects.requireNonNull(runtimeEnvironment, "runtimeEnvironment");
        this.bridge = new SandboxWorkspaceBridge(srtCli, nodeExe, objectMapper, timeout, runtimeEnvironment,
                settingsFile, requestDirectory, applicationDirectory);
    }

    @Override public String id() { return id; }
    @Override public String workDir() { return root.toString(); }
    @Override public RuntimeEnvironment runtimeEnvironment() { return environment; }
    @Override public SandboxWorkspaceBridge bridge() { return bridge; }

    @Override
    public Path resolve(String path) {
        return path == null || path.isBlank() ? root : root.resolve(path).normalize();
    }

    @Override
    public boolean encloses(Path path) {
        return path != null && path.toAbsolutePath().normalize().startsWith(root);
    }
}
