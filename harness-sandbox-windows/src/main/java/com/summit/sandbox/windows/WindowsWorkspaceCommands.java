package com.summit.sandbox.windows;

import com.summit.core.runtime.CommandHandler;
import com.summit.core.runtime.ProcessCoordinator;
import com.summit.core.runtime.workspace.ShellType;
import com.summit.core.runtime.workspace.WorkspaceCommands;

import java.io.IOException;
import java.nio.charset.Charset;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.TimeUnit;

/**
 * {@link WorkspaceCommands} adapter backed by {@link WindowsSandboxRunner}.
 */
final class WindowsWorkspaceCommands implements WorkspaceCommands {
    private static final long OUTPUT_LIMIT = 4L * 1024 * 1024;

    private final Path workspaceRoot;
    private final SandboxMode mode;
    private final Map<String, String> environment;

    WindowsWorkspaceCommands(Path workspaceRoot, SandboxMode mode, Map<String, String> environment) {
        this.workspaceRoot = Objects.requireNonNull(workspaceRoot, "workspaceRoot").toAbsolutePath().normalize();
        this.mode = Objects.requireNonNull(mode, "mode");
        this.environment = Map.copyOf(Objects.requireNonNull(environment, "environment"));
    }

    @Override
    public CommandHandler startCommand(
            String command,
            ShellType shell,
            String workDir,
            Charset charset,
            long timeout,
            TimeUnit unit,
            long maxChars
    ) throws IOException {
        if (timeout <= 0 || unit == null) {
            throw new IllegalArgumentException("Execution timeout must be positive");
        }
        if (maxChars < 0 || maxChars > OUTPUT_LIMIT) {
            throw new IllegalArgumentException("Output limit must be between 0 and 4 Mi characters");
        }
        Objects.requireNonNull(command, "command must not be null");
        Objects.requireNonNull(charset, "charset must not be null");

        ShellType selected = shell == null ? ShellType.CMD : shell;
        List<String> argv = selected.buildCommand(command);
        Path resolvedWorkDir = (workDir == null || workDir.isBlank())
                ? workspaceRoot
                : resolveEnclosedWorkDir(workDir);

        return WindowsSandboxRunner.start(
                workspaceRoot,
                resolvedWorkDir,
                mode,
                argv,
                environment,
                Duration.ofMillis(Math.max(1L, unit.toMillis(timeout))),
                charset,
                maxChars,
                WindowsSandboxAccount.usernameFor(mode),
                WindowsSandboxCredentialStore.defaultCredentialFile(mode)
        );
    }

    @Override
    public CommandResult execute(
            String command,
            ShellType shell,
            String workDir,
            Charset charset,
            long timeout,
            TimeUnit unit,
            long maxChars
    ) throws IOException, InterruptedException {
        CommandHandler handle = startCommand(command, shell, workDir, charset, timeout, unit, maxChars);
        return ProcessCoordinator.waitFor(handle);
    }

    private Path resolveEnclosedWorkDir(String workDir) {
        Path candidate = Path.of(workDir);
        Path normalized = (candidate.isAbsolute() ? candidate : workspaceRoot.resolve(candidate))
                .toAbsolutePath()
                .normalize();
        if (!normalized.startsWith(workspaceRoot)) {
            throw new IllegalArgumentException("Working directory escapes workspace root: " + workDir);
        }
        return normalized;
    }
}
