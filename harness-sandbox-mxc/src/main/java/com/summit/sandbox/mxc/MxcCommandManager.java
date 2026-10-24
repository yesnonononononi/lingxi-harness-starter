package com.summit.sandbox.mxc;

import com.summit.core.runtime.CommandHandler;
import com.summit.core.runtime.ProcessCoordinator;
import com.summit.core.runtime.workspace.ShellType;
import com.summit.core.runtime.workspace.WorkspaceCommands;
import java.io.IOException;
import java.nio.charset.Charset;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.TimeUnit;

/** Synchronous and background commands use the same policy-bound launch and completion path. */
final class MxcCommandManager implements WorkspaceCommands {
    private static final long OUTPUT_LIMIT = 4 * 1024 * 1024;
    private final MxcRuntime runtime;
    private final MxcTransport transport;

    MxcCommandManager(MxcRuntime runtime, MxcTransport transport) { this.runtime = runtime; this.transport = transport; }

    @Override public CommandHandler startCommand(String command, ShellType shell, String workDir, Charset charset,
                                                 long timeout, TimeUnit unit, long maxChars) throws IOException {
        if (timeout <= 0 || unit == null) throw new IllegalArgumentException("Execution timeout must be positive");
        if (maxChars < 0 || maxChars > OUTPUT_LIMIT) throw new IllegalArgumentException("Output limit must be between 0 and 4 Mi characters");
        Objects.requireNonNull(charset, "charset must not be null");
        Map<String, Object> arguments = new LinkedHashMap<>();
        arguments.put("command", Objects.requireNonNull(command, "command must not be null"));
        // The argv is built once by ShellType; the launcher only resolves argv[0] inside the sandbox.
        ShellType selected = shell == null ? (runtime.windows() ? ShellType.CMD : ShellType.BASH) : shell;
        arguments.put("shell", selected.id());
        arguments.put("shellArgv", selected.buildCommand(command));
        if (workDir != null && !workDir.isBlank()) arguments.put("workDir", runtime.nativePath(workDir));
        return transport.start("execute", arguments, Duration.ofMillis(Math.max(1, unit.toMillis(timeout))), charset, (int) maxChars);
    }

    @Override public CommandResult execute(String command, ShellType shell, String workDir, Charset charset,
                                          long timeout, TimeUnit unit, long maxChars) throws IOException, InterruptedException {
        CommandHandler handle = startCommand(command, shell, workDir, charset, timeout, unit, maxChars);
       return ProcessCoordinator.waitFor(handle);
    }
}