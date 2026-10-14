package com.summit.core.runtime.workspace;

import java.io.IOException;
import java.nio.charset.Charset;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.TimeUnit;

/**
 * Abstraction for the file system and process operations a tool executor
 * performs inside a {@link Workspace}.
 *
 * <p>The {@link Workspace} interface describes <em>where</em> the agent works
 * (paths, environment, boundary enforcement); a {@code WorkspaceBridge}
 * describes <em>how</em> IO and commands physically reach that place. The
 * default bridge operates on the local machine; alternative implementations
 * can route the very same operations into an isolated execution environment
 * without tool executors or the framework knowing.</p>
 *
 * <p>All {@link Path} arguments are workspace paths already resolved by
 * {@link Workspace#resolve(String)}; each bridge interprets them within its
 * own execution environment.</p>
 */
public interface WorkspaceBridge {

    /**
     * Returns whether the given path exists. Implementations may throw
     * {@link java.io.UncheckedIOException} when their backend cannot determine
     * existence; execution or transport failure must not be reported as absence.
     */
    boolean exists(Path path);

    /** Creates the directory and all non-existing parents. */
    void createDirectories(Path path) throws IOException;

    /** Deletes the file (not directories) at the given path; no-op when missing. */
    void deleteFile(Path path) throws IOException;

    /** Reads the whole file content as a string. */
    String readString(Path path, Charset charset) throws IOException;

    /** Splits bridge-provided text, preserving blank lines and stripping CR, LF or CRLF terminators. */
    default List<String> readLines(Path path, Charset charset) throws IOException {
        return readString(path, charset).lines().toList();
    }

    /** Writes (or fully replaces) the file content. Creates the file when missing. */
    void writeString(Path path, String content, Charset charset) throws IOException;

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
     * @param command          raw command text, without a framework shell wrapper
     * @param workDir          host-side working directory for the process; may be
     *                         {@code null} when the bridge does not need one
     *                         (e.g. the target dir is encoded in the command)
     * @param charset          charset used to decode the process output
     * @param timeoutSeconds   maximum time to wait, expressed in {@code timeUnit}
     * @param timeUnit         unit of the execution timeout
     * @param maxOutputChars   output buffer budget in chars; beyond it output
     *                         is discarded and {@link CommandResult#truncated()}
     *                         is set
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
