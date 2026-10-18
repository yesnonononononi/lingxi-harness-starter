package com.summit.core.runtime.workspace;

import com.summit.core.runtime.CommandHandler;
import com.summit.core.runtime.ProcessRunner;
import com.summit.core.runtime.RuntimeEnvironment;

import java.io.IOException;
import java.nio.charset.Charset;
import java.util.Objects;
import java.util.concurrent.TimeUnit;

public final class LocalWorkspaceCommands implements WorkspaceCommands {
    private final RuntimeEnvironment environment;

    public LocalWorkspaceCommands(RuntimeEnvironment environment) {
        this.environment = Objects.requireNonNull(environment, "workspace environment");
        Objects.requireNonNull(environment.shellType(), "workspace shellType must not be null");
    }

    @Override
    public CommandResult execute(String command, String workDir, Charset charset,
                                 long timeoutSeconds, TimeUnit timeUnit, long maxOutputChars)
            throws IOException, InterruptedException {
        return ProcessRunner.run(environment.buildCommand(Objects.requireNonNull(command, "command must not be null")),
                workDir, charset, timeoutSeconds, timeUnit, maxOutputChars);
    }
    @Override
    public CommandHandler startCommand(String command, String workDir, Charset charset,
                                       long timeout, TimeUnit unit, long maxChars) throws IOException {
        return ProcessRunner.start(environment.buildCommand(Objects.requireNonNull(command, "command must not be null")),
                workDir, charset, new byte[0], timeout, unit, maxChars);
    }
}