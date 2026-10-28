package com.summit.core.runtime;

import com.summit.core.runtime.workspace.WorkspaceCommands;

import java.io.IOException;
import java.nio.charset.Charset;
import java.util.List;
import java.util.concurrent.TimeUnit;

/**
 * Stateless convenience calls; every invocation owns a fresh cancellable runner.
 */
public final class ProcessRunner {
    private ProcessRunner() {
    }

    /**
     * Runs an independent command with a timeout expressed in seconds.
     */
    public static WorkspaceCommands.CommandResult run(List<String> command, String workDir, Charset charset,
                                                      long timeoutSeconds, long maxOutputChars)
            throws IOException, InterruptedException {
        return run(command, workDir, charset, timeoutSeconds, TimeUnit.SECONDS, maxOutputChars);
    }

    /**
     * Runs an independent command using the caller's timeout unit.
     */
    public static WorkspaceCommands.CommandResult run(List<String> command, String workDir, Charset charset,
                                                      long timeout, TimeUnit unit, long maxOutputChars)
            throws IOException, InterruptedException {
        return run(command, workDir, charset, new byte[0], timeout, unit, maxOutputChars);
    }

    /**
     * Uses the same execution path as caller-owned runners without retaining their lifecycle.
     */
    public static WorkspaceCommands.CommandResult run(List<String> command, String workDir, Charset charset,
                                                      byte[] stdin, long timeout, TimeUnit unit, long maxOutputChars)
            throws IOException, InterruptedException {
        return new CancellableProcessRunner().run(command, workDir, charset, stdin, timeout, unit, maxOutputChars);
    }


    /** Starts an independently owned command with autonomous timeout and cleanup. */
    public static CommandHandler start(List<String> command, String workDir, Charset charset, byte[] stdin,
                                       long timeout, TimeUnit unit, long maxOutputChars) throws IOException {
        return new CancellableProcessRunner().start(command, workDir, charset, stdin, timeout, unit, maxOutputChars);
    }
}