package com.summit.runtime.workspace;

import com.summit.core.runtime.LocalWorkspaceBridge;
import com.summit.core.runtime.OsType;
import com.summit.core.runtime.RuntimeEnvironment;
import com.summit.core.runtime.ShellType;
import com.summit.core.runtime.Workspace;
import com.summit.core.runtime.WorkspaceBridge;

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

    @Override
    public Path resolve(String path) {
        Path target = path == null || path.isBlank()
                ? root
                : (Paths.get(path).isAbsolute() ? Paths.get(path) : root.resolve(path)).normalize();
        if (!target.startsWith(root)) {
            throw new IllegalArgumentException("File path is out of workspace: " + path);
        }
        return target;
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
