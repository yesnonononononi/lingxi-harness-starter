package com.summit.harnesscore.runtime;

import java.io.IOException;
import java.nio.charset.Charset;
import java.nio.file.Path;
import java.util.List;

/**
 * Abstraction for the file system and process operations a tool executor
 * performs inside a {@link Workspace}.
 *
 * <p>The {@link Workspace} interface describes <em>where</em> the agent works
 * (paths, environment, boundary enforcement); a {@code WorkspaceBridge}
 * describes <em>how</em> IO and commands physically reach that place. The
 * default bridge operates on the local machine; alternative implementations
 * can route the very same operations into an isolated sandbox such as a
 * Docker container, without tool executors or the framework knowing.</p>
 *
 * <p>All {@link Path} arguments are workspace paths already resolved by
 * {@link Workspace#resolve(String)}; each bridge interprets them within its
 * own execution environment.</p>
 */
public interface WorkspaceBridge {

    /** Returns whether the given path exists. */
    boolean exists(Path path);

    /** Creates the directory and all non-existing parents. */
    void createDirectories(Path path) throws IOException;

    /** Creates an empty file if it does not exist yet; no-op when it does. */
    void createFile(Path path) throws IOException;

    /** Reads the whole file content as a string. */
    String readString(Path path, Charset charset) throws IOException;

    /** Reads the whole file content as lines (line terminators stripped). */
    List<String> readLines(Path path, Charset charset) throws IOException;

    /** Writes (or fully replaces) the file content. Creates the file when missing. */
    void writeString(Path path, String content, Charset charset) throws IOException;

    /**
     * Executes a fully-formed command (including the shell wrapper if any) and
     * returns its combined stdout/stderr.
     *
     * @param command          command and arguments, e.g. {@code [sh, -c, ls]}
     * @param workDir          host-side working directory for the process; may be
     *                         {@code null} when the bridge does not need one
     *                         (e.g. the target dir is encoded in the command)
     * @param charset          charset used to decode the process output
     * @param timeoutSeconds   maximum seconds to wait for process completion
     * @param maxOutputChars   output buffer budget in chars; beyond it output
     *                         is discarded and {@link CommandResult#truncated()}
     *                         is set
     * @return the command result, never {@code null}
     */
    CommandResult execute(List<String> command, String workDir, Charset charset,
                          long timeoutSeconds, long maxOutputChars)
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
    }
}
