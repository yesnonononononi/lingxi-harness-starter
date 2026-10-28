package com.summit.core.runtime;

import com.summit.core.runtime.workspace.WorkspaceCommands.CommandResult;
import java.io.IOException;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.TimeUnit;

/** Owns at most one command; completion releases the runner before publishing its result. */
public final class CancellableProcessRunner {
    private ProcessResult active;

    public CommandResult run(List<String> command, String workDir, Charset charset, byte[] stdin,
                             long timeout, TimeUnit unit, long maxChars) throws IOException, InterruptedException {
        return ProcessCoordinator.waitFor(start(command, workDir, charset, stdin, timeout, unit, maxChars));
    }

    public CommandHandler start(List<String> command, String workDir, Charset charset, byte[] stdin,
                                long timeout, TimeUnit unit, long maxChars) throws IOException {
        return start(command, workDir, charset, stdin, timeout, unit, maxChars, null);
    }

    public CommandHandler start(List<String> command, String workDir, Charset charset, byte[] stdin,
                                long timeout, TimeUnit unit, long maxChars, ProcessOutputListener listener) throws IOException {
        if (timeout <= 0 || unit == null) throw new IllegalArgumentException("Timeout must be positive and have a time unit");
        return startInternal(command, workDir, charset, stdin, timeout, unit, maxChars, listener);
    }

    private synchronized ProcessResult startInternal(List<String> command, String workDir, Charset charset, byte[] stdin,
                                                     long timeout, TimeUnit unit, long maxChars, ProcessOutputListener listener) throws IOException {
        Objects.requireNonNull(stdin, "stdin must not be null");
        Objects.requireNonNull(charset, "charset must not be null");
        Objects.requireNonNull(command, "command must not be null");
        if (command.isEmpty() || command.stream().anyMatch(Objects::isNull)) throw new IllegalArgumentException("Command argv must not be empty or contain null");
        if (maxChars < 0) throw new IllegalArgumentException("Output limit must not be negative");
        if (active != null) throw new IllegalStateException("A command is already running on this runner");
        long started = System.nanoTime();
        ProcessBuilder builder = new ProcessBuilder(command).redirectErrorStream(true);
        if (workDir != null) builder.directory(Path.of(workDir).toFile());
        ProcessResult result = new ProcessResult(builder.start());
        active = result;
        try {
            result.begin(charset, stdin, maxChars, timeout, unit, started, listener, () -> release(result));
            return result;
        } catch (RuntimeException | Error failure) {
            try { ProcessCoordinator.cleanup(result); }
            catch (IOException | InterruptedException cleanup) { failure.addSuppressed(cleanup); }
            release(result);
            throw failure;
        }
    }

    private synchronized void release(ProcessResult result) { if (active == result) active = null; }



    public CommandResult run(String[] command) throws IOException {
        try { return ProcessCoordinator.waitFor(startInternal(List.of(command), null, StandardCharsets.UTF_8, new byte[0], 0, null, Long.MAX_VALUE, null)); }
        catch (InterruptedException failure) { throw new IOException("Command execution interrupted", failure); }
    }
    public CommandResult run(String[] command, long timeout, TimeUnit unit) throws IOException {
        return run(command, new byte[0], timeout, unit, Long.MAX_VALUE);
    }
    public CommandResult run(String[] command, byte[] stdin, long timeout, TimeUnit unit) throws IOException {
        return run(command, stdin, timeout, unit, Long.MAX_VALUE);
    }
    public CommandResult run(String[] command, byte[] stdin, long timeout, TimeUnit unit, long maxChars) throws IOException {
        try { return run(List.of(command), null, StandardCharsets.UTF_8, stdin, timeout, unit, maxChars); }
        catch (InterruptedException failure) { throw new IOException("Command execution interrupted", failure); }
    }
    /** Requests cancellation; use the command handle's stop method when confirmation is required. */
    public synchronized boolean cancel() { return active != null && active.requestStop(); }
}