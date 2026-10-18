package com.summit.core.runtime.workspace;

import com.summit.core.runtime.CommandHandler;

import java.io.IOException;
import java.nio.charset.Charset;
import java.util.concurrent.TimeUnit;

/** Command execution must retain the owning workspace's shell and isolation policy. */
public interface WorkspaceCommands {

    /**
     * Executes raw command text in this workspace's configured shell and returns
     * its combined stdout/stderr.
     *
     * <p>Callers pass the original command text, for example {@code git status},
     * without adding {@code powershell -Command}, {@code cmd /c}, or {@code sh -c}.
     * The bridge owns shell selection from {@link Workspace#runtimeEnvironment()},
     * shell invocation, quoting, and transport into its execution environment.
     * Callers and models must not probe shells or assemble process argument lists.</p>
     *
     * <p>The entire string is one shell script. Preserve its whitespace, quotes,
     * variables, pipelines, and line breaks; do not split it into tokens or escape
     * its operators as literal arguments. Script syntax must match the shell
     * advertised by the workspace. Shell selection and wrapping are separate
     * from authorization: the configured sandbox must still enforce its policy.</p>
     *
     * @param command        raw command text, without a framework shell wrapper
     * @param workDir        host-side working directory for the process; may be
     *                       {@code null} when the bridge does not need one
     *                       (e.g. the target dir is encoded in the command)
     * @param charset        charset used to decode the process output
     * @param timeoutSeconds maximum time to wait, expressed in {@code timeUnit}
     * @param timeUnit       unit of the execution timeout
     * @param maxOutputChars output buffer budget in chars; beyond it output
     *                       is discarded and {@link CommandResult#truncated()}
     *                       is set
     * @return the command result, never {@code null}
     */
    CommandResult execute(String command,
                          String workDir,
                          Charset charset,
                          long timeoutSeconds,
                          TimeUnit timeUnit,
                          long maxOutputChars
    )
            throws IOException, InterruptedException;

    /** Starts a bounded background command; startup and interruption failures remain observable. */
    default CommandHandler startCommand(
            String command,
            String workDir,
            Charset charset,
            long timeoutSeconds,
            TimeUnit timeUnit,
            long maxOutputChars
    ) throws IOException, InterruptedException {
        throw new UnsupportedOperationException("Background command execution is not supported by this runtime");
    }

    /**
     * Result of a command executed through this bridge.
     *
     * @param exitCode  process exit code; meaningless when {@code timedOut}
     * @param output    combined stdout/stderr, optionally with exit code suffix
     * @param truncated whether the output exceeded the char budget
     * @param timedOut  whether the process was killed due to timeout
     */
    record CommandResult(int exitCode, String output, boolean truncated, boolean timedOut) {

        public boolean isSuccess() {
            return exitCode == 0;
        }
    }
}
