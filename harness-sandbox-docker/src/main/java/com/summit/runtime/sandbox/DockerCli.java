package com.summit.runtime.sandbox;

import com.summit.core.runtime.ProcessRunner;
import com.summit.core.runtime.workspace.WorkspaceBridge.CommandResult;
import lombok.extern.slf4j.Slf4j;

import java.io.IOException;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.util.List;

/**
 * Single gateway to the {@code docker} CLI: every container, image, label or
 * mount query of this module funnels through here, so process invocation,
 * charset and timeout live in exactly one place.
 *
 * <p>The CLI itself runs on the host, therefore its output is always decoded as
 * UTF-8 — the workspace charset only applies to file contents <em>inside</em>
 * the container.</p>
 */
@Slf4j
public final class DockerCli {

    /** Charset of the docker CLI output. */
    public static final Charset CHARSET = StandardCharsets.UTF_8;

    /** Maximum seconds a single docker CLI call may take. */
    public static final long TIMEOUT_SECONDS = 60;

    private DockerCli() {
    }

    /** Runs a docker command, failing when it times out or exits non-zero. */
    public static void run(List<String> command) throws IOException {
        CommandResult result = execute(command);
        if (result.timedOut() || result.exitCode() != 0) {
            throw failure(command, result);
        }
    }

    /** Runs a docker command and returns its raw output, failing when the command does. */
    public static byte[] outputBytes(List<String> command) throws IOException {
        CommandResult result = execute(command);
        if (result.timedOut() || result.exitCode() != 0) {
            throw failure(command, result);
        }
        return result.output().getBytes(CHARSET);
    }

    /** {@link #outputBytes(List)} decoded with {@link #CHARSET}. */
    public static String output(List<String> command) throws IOException {
        return new String(outputBytes(command), CHARSET);
    }

    /** {@link #output(List)} without leading/trailing whitespace. */
    public static String trimmedOutput(List<String> command) throws IOException {
        return output(command).trim();
    }

    /**
     * Exit code of a docker command, or {@code -1} when it could not be run at
     * all (missing container, offline daemon, timeout).
     */
    public static int exitCode(List<String> command) {
        try {
            CommandResult result = execute(command);
            return result.timedOut() ? -1 : result.exitCode();
        } catch (IOException e) {
            log.debug("docker command failed: {} -> {}", String.join(" ", command), e.getMessage());
            return -1;
        }
    }

    /** {@link #trimmedOutput(List)} for callers that must fail hard instead of handling an {@link IOException}. */
    public static String require(List<String> command) {
        try {
            return trimmedOutput(command);
        } catch (IOException e) {
            throw new RuntimeException("docker command failed: " + String.join(" ", command), e);
        }
    }

    private static CommandResult execute(List<String> command) throws IOException {
        try {
            return ProcessRunner.run(command, null, CHARSET, TIMEOUT_SECONDS, Long.MAX_VALUE);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IOException("docker command interrupted: " + String.join(" ", command), e);
        }
    }

    private static IOException failure(List<String> command, CommandResult result) {
        return new IOException("docker command failed (" + result.exitCode() + "): "
                + String.join(" ", command) + " -> " + result.output());
    }
}
