package com.summit.core.runtime;

import com.summit.core.runtime.workspace.WorkspaceBridge;

import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.io.Reader;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Shared host-process execution for local and sandbox workspace bridges.
 * Sends stdin concurrently with bounded, charset-aware output draining.
 */
public class ProcessRunner {
    private static final Duration CLEANUP_TIMEOUT = Duration.ofSeconds(2);
    private volatile Process process;

    /** Runs an independent command with a timeout expressed in seconds. */
    public static WorkspaceBridge.CommandResult run(List<String> command, String workDir, Charset charset,
                                                     long timeoutSeconds, long maxOutputChars)
            throws IOException, InterruptedException {
        return run(command, workDir, charset, timeoutSeconds, TimeUnit.SECONDS, maxOutputChars);
    }

    /** Runs an independent command using the caller's timeout unit. */
    public static WorkspaceBridge.CommandResult run(List<String> command, String workDir, Charset charset,
                                                     long timeout, TimeUnit unit, long maxOutputChars)
            throws IOException, InterruptedException {
        return run(command, workDir, charset, new byte[0], timeout, unit, maxOutputChars);
    }

    /** Runs an independent command with stdin, a working directory and output decoding settings. */
    public static WorkspaceBridge.CommandResult run(List<String> command, String workDir, Charset charset,
                                                     byte[] stdin, long timeout, TimeUnit unit, long maxOutputChars)
            throws IOException, InterruptedException {
        validate(stdin, charset, timeout, unit, maxOutputChars);
        return new ProcessRunner().runInternal(command.toArray(String[]::new), workDir, charset,
                stdin, timeout, unit, maxOutputChars);
    }

    /** Runs a cancellable command without an execution timeout, closing empty stdin. */
    public WorkspaceBridge.CommandResult run(String[] command) throws IOException {
        return runCommand(command, new byte[0], 0, null, Long.MAX_VALUE);
    }

    public WorkspaceBridge.CommandResult run(String[] command, long timeout, TimeUnit unit) throws IOException {
        return run(command, new byte[0], timeout, unit, Long.MAX_VALUE);
    }

    public WorkspaceBridge.CommandResult run(String[] command, byte[] stdin, long timeout, TimeUnit unit)
            throws IOException {
        return run(command, stdin, timeout, unit, Long.MAX_VALUE);
    }

    /**
     * Runs a cancellable command with UTF-8 output and an optional character budget.
     * Input delivery is covered by the execution timeout; closing stdin signals EOF.
     */
    public WorkspaceBridge.CommandResult run(String[] command, byte[] stdin, long timeout, TimeUnit unit,
                                             long maxOutputChars) throws IOException {
        validate(stdin, StandardCharsets.UTF_8, timeout, unit, maxOutputChars);
        return runCommand(command, stdin, timeout, unit, maxOutputChars);
    }

    private WorkspaceBridge.CommandResult runCommand(String[] command, byte[] stdin, long timeout, TimeUnit unit,
                                                      long maxOutputChars) throws IOException {
        try {
            return runInternal(command, null, StandardCharsets.UTF_8, stdin, timeout, unit, maxOutputChars);
        } catch (InterruptedException e) {
            throw new IOException("Command execution interrupted", e);
        }
    }

    private static void validate(byte[] stdin, Charset charset, long timeout, TimeUnit unit, long maxOutputChars) {
        if (timeout <= 0 || unit == null) {
            throw new IllegalArgumentException("Timeout must be positive and have a time unit");
        }
        Objects.requireNonNull(stdin, "stdin must not be null");
        Objects.requireNonNull(charset, "charset must not be null");
        if (maxOutputChars < 0) {
            throw new IllegalArgumentException("Output limit must not be negative");
        }
    }

    private WorkspaceBridge.CommandResult runInternal(String[] command, String workDir, Charset charset,
                                                       byte[] stdin, long timeout, TimeUnit unit,
                                                       long maxOutputChars) throws IOException, InterruptedException {
        Process child = start(command, workDir);
        long started = System.nanoTime();
        StringBuilder output = new StringBuilder();
        AtomicBoolean truncated = new AtomicBoolean();
        AtomicReference<IOException> readFailure = new AtomicReference<>();
        AtomicReference<IOException> writeFailure = new AtomicReference<>();
        Thread outputThread = null;
        Thread inputThread = null;

        try {

            outputThread = Thread.ofVirtual().start(() -> {
                try (Reader reader = new InputStreamReader(child.getInputStream(), charset)) {
                    char[] chars = new char[4096];
                    int count;
                    while ((count = reader.read(chars)) != -1) {
                        int keep = (int) Math.min(count, maxOutputChars - output.length());
                        if (keep > 0) {
                            output.append(chars, 0, keep);
                        }
                        if (keep < count) {
                            truncated.set(true);
                        }
                        // Continue draining discarded output so the child cannot block on a full pipe.
                    }
                } catch (IOException e) {
                    readFailure.set(e);
                    if (child.isAlive()) {
                        requestTermination(child);
                    }
                }
            });


            inputThread = Thread.ofVirtual().start(() -> {
                // Closing even an empty input is necessary for helpers waiting for EOF.
                try (OutputStream input = child.getOutputStream()) {
                    input.write(stdin);
                } catch (IOException e) {
                    writeFailure.set(e);
                    if (child.isAlive()) {
                        requestTermination(child);
                    }
                }
            });

            boolean completed;
            if (unit == null) {
                child.waitFor();
                completed = true;
            } else {
                long remaining = unit.toNanos(timeout) - (System.nanoTime() - started);
                completed = child.waitFor(Math.max(0, remaining), TimeUnit.NANOSECONDS);
            }
            if (!completed) {
                terminateTree(child);
            }
            joinIoThreads(inputThread, outputThread);
            if (completed && readFailure.get() != null) {
                throw new IOException("Failed to read command output", readFailure.get());
            }
            // Keep the command's own nonzero exit and diagnostics when it rejects input early.
            if (completed && child.exitValue() == 0 && writeFailure.get() != null) {
                throw new IOException("Failed to write command stdin", writeFailure.get());
            }
            return new WorkspaceBridge.CommandResult(completed ? child.exitValue() : -1,
                    output.toString(), truncated.get(), !completed);
        } catch (InterruptedException e) {
            requestTermination(child);
            Thread.currentThread().interrupt();
            throw e;

        } finally {
            if (child.isAlive()) {
                requestTermination(child);
            }
            closeStreams(child);
            if (inputThread != null) {
                inputThread.interrupt();
            }
            if (outputThread != null) {
                outputThread.interrupt();
            }
            synchronized (this) {
                process = null;
            }
        }
    }

    private static void joinIoThreads(Thread inputThread, Thread outputThread)
            throws InterruptedException, IOException {
        long deadline = System.nanoTime() + CLEANUP_TIMEOUT.toNanos();
        for (Thread thread : List.of(inputThread, outputThread)) {
            long remaining = deadline - System.nanoTime();
            if (thread.isAlive() && remaining > 0) {
                thread.join(Duration.ofNanos(remaining));
            }
            if (thread.isAlive()) {
                throw new IOException("Command input/output stream did not close after process termination");
            }
        }
    }

    private synchronized Process start(String[] command, String workDir) throws IOException {
        if (process != null) {
            throw new IllegalStateException("A command is already running on this runner");
        }
        ProcessBuilder builder = new ProcessBuilder(command).redirectErrorStream(true);
        if (workDir != null) {
            builder.directory(Path.of(workDir).toFile());
        }
        process = builder.start();
        return process;
    }

    private static List<ProcessHandle> requestTermination(Process child) {
        List<ProcessHandle> handles = new ArrayList<>(child.descendants().toList());
        // Capture descendants before killing their parent, while their relationship is visible.
        for (int i = handles.size() - 1; i >= 0; i--) {
            handles.get(i).destroyForcibly();
        }
        handles.add(child.toHandle());
        child.destroyForcibly();
        return handles;
    }

    private static void terminateTree(Process child) throws IOException, InterruptedException {
        List<ProcessHandle> handles = requestTermination(child);
        long deadline = System.nanoTime() + CLEANUP_TIMEOUT.toNanos();
        for (ProcessHandle handle : handles) {
            if (!handle.isAlive()) {
                continue;
            }
            long remaining = deadline - System.nanoTime();
            if (remaining <= 0) {
                throw new IOException("Timed-out command process could not be terminated: " + handle.pid());
            }
            try {
                handle.onExit().get(remaining, TimeUnit.NANOSECONDS);
            } catch (ExecutionException | TimeoutException e) {
                throw new IOException("Timed-out command process could not be terminated: " + handle.pid(), e);
            }
        }
    }

    private static void closeStreams(Process child) {
        try { child.getInputStream().close(); } catch (IOException ignored) { }
        try { child.getErrorStream().close(); } catch (IOException ignored) { }
        try { child.getOutputStream().close(); } catch (IOException ignored) { }
    }

    /** Waits for any in-progress start to publish its handle, then requests process-tree termination. */
    public synchronized boolean cancel() {
        Process active = process;
        if (active == null || !active.isAlive()) {
            return false;
        }
        requestTermination(active);
        return true;
    }
}
