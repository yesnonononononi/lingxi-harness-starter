package com.summit.runtime.workspace;

import com.summit.core.runtime.workspace.LocalWorkspaceBridge;
import com.summit.core.runtime.workspace.OsType;
import com.summit.core.runtime.RuntimeEnvironment;
import com.summit.core.runtime.workspace.ShellType;
import com.summit.core.runtime.workspace.Workspace;
import com.summit.core.runtime.workspace.WorkspaceBridge;

import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.nio.file.Paths;

/** Framework-provided, boundary-confined local workspace. */
public final class LocalWorkspace implements Workspace {
    private final String id;
    private final Path root;
    private final RuntimeEnvironment environment;

    public LocalWorkspace(String id, String workDir) {
        if (id == null || id.isBlank()) {
            throw new IllegalArgumentException("workspace id must not be blank");
        }
        if (workDir == null || workDir.isBlank()) {
            throw new IllegalArgumentException("workspace workDir must not be blank");
        }
        this.id = id;
        this.root = Paths.get(workDir).toAbsolutePath().normalize();
        this.environment = hostEnvironment();
    }

    @Override
    public String id() {
        return id;
    }

    @Override
    public RuntimeEnvironment runtimeEnvironment() {
        return environment;
    }

    @Override
    public String workDir() {
        return root.toString();
    }

    /** Purely geometric: relative paths are appended to the workspace root, absolute paths are returned as-is. Whether an outside path may be used at all is decided by the caller. */
    @Override
    public Path resolve(String path) {
        if (path == null || path.isBlank()) {
            return root;
        }
        Path p = Paths.get(path);
        return (p.isAbsolute() ? p : root.resolve(path)).normalize();
    }

    /** Paths are compared against the normalized root held by this instance. */
    @Override
    public boolean encloses(Path path) {
        return path != null && path.toAbsolutePath().normalize().startsWith(root);
    }

    @Override
    public WorkspaceBridge bridge() {
        return LocalWorkspaceBridge.INSTANCE;
    }

    private static RuntimeEnvironment hostEnvironment() {
        String os = System.getProperty("os.name", "").toLowerCase();
        OsType osType = os.contains("win") ? OsType.WINDOWS
                : os.contains("mac") ? OsType.MACOS : OsType.LINUX;
        ShellType shell = os.contains("win") ? ShellType.CMD
                : os.contains("mac") ? ShellType.ZSH : ShellType.BASH;
        return RuntimeEnvironment.builder()
                .osType(osType)
                .shellType(shell)
                .charset(StandardCharsets.UTF_8)
                .build();
    }
}
