package com.summit.harnessexample;

import com.summit.core.runtime.workspace.OsType;
import com.summit.core.runtime.RuntimeEnvironment;
import com.summit.core.runtime.workspace.ShellType;
import com.summit.core.runtime.workspace.Workspace;
import org.jspecify.annotations.NonNull;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

@Component
@ConditionalOnProperty(name = "lingxi.agent.workspace", havingValue = "local", matchIfMissing = true)
public class LocalWorkSpace implements Workspace {
    private volatile String workDir = null;

    @Override
    public String id() {
        return "local";
    }

    @Override
    public RuntimeEnvironment runtimeEnvironment() {
        return RuntimeEnvironment.builder()
                .osType(OsType.WINDOWS)
                .shellType(ShellType.PWSH)
                .charset(StandardCharsets.UTF_8)
                .envs(System.getenv())
                .build();
    }

    @Override
    public String workDir() {
        return workDir == null ? System.getProperty("user.dir") : workDir;
    }

    /** Geometric resolution only: whether an outside path may be used is decided by the caller, using {@link #encloses(Path)} and the per-request switch. */
    @Override
    public Path resolve(@NonNull String path) {
        Path root = Paths.get(workDir()).toAbsolutePath().normalize();
        if (path == null || path.isBlank()) {
            return root;
        }
        Path input = Paths.get(path);
        return (input.isAbsolute() ? input : root.resolve(path)).normalize();
    }

    /** Switches the workspace working directory. */
    public void updateWorkDir(String workDir) {
        if (workDir == null || workDir.isBlank()) {
            throw new IllegalArgumentException("workDir must not be blank");
        }
        Path dir = Paths.get(workDir).normalize();
        if (!Files.isDirectory(dir)) {
            throw new IllegalArgumentException("workDir does not exist or is not a directory: " + dir);
        }
        this.workDir = dir.toAbsolutePath().normalize().toString();
    }
}
