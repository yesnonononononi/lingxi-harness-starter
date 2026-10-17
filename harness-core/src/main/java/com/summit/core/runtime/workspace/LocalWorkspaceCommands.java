package com.summit.core.runtime.workspace;

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
}
