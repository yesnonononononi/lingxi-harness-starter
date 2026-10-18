package com.summit.core.runtime;

import com.summit.core.runtime.workspace.WorkspaceCommands.CommandResult;

import java.io.IOException;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/**
 * Shared termination and I/O settlement for caller-owned process handles.
 */
public final class ProcessCoordinator {
    private static final Duration CLEANUP_TIMEOUT = Duration.ofSeconds(2);

    private ProcessCoordinator() {
    }

    static CommandResult postProcess(ProcessResult result, TimeUnit unit, long timeout, long started)
            throws IOException, InterruptedException {
        Process child = result.child;
        boolean completed;
        if (unit == null) {
            child.waitFor();
            completed = true;
        } else
            completed = child.waitFor(Math.max(0, unit.toNanos(timeout) - (System.nanoTime() - started)), TimeUnit.NANOSECONDS);
        if (!completed) terminateTree(child);
        joinIoThreads(result.inputThread, result.outputThread);
        if (completed && !result.stopping.get()) {
            if (result.readFailure.get() != null)
                throw new IOException("Failed to read command output", result.readFailure.get());
            if (child.exitValue() == 0 && result.writeFailure.get() != null)
                throw new IOException("Failed to write command stdin", result.writeFailure.get());
        }
        return new CommandResult(completed ? child.exitValue() : -1, result.output.toString(), result.truncated.get(), !completed);
    }

    public static List<ProcessHandle> requestTermination(Process child) {
        if (child == null) return List.of();
        List<ProcessHandle> handles = new ArrayList<>(child.descendants().toList());
        for (int index = handles.size() - 1; index >= 0; index--) handles.get(index).destroyForcibly();
        handles.add(child.toHandle());
        child.destroyForcibly();
        return handles;
    }

    public static void terminateTree(Process child) throws IOException, InterruptedException {
        List<ProcessHandle> handles = requestTermination(child);
        long deadline = System.nanoTime() + CLEANUP_TIMEOUT.toNanos();
        for (ProcessHandle handle : handles) {
            if (!handle.isAlive()) continue;
            long remaining = deadline - System.nanoTime();
            if (remaining <= 0) throw new IOException("Command process could not be terminated: " + handle.pid());
            try {
                handle.onExit().get(remaining, TimeUnit.NANOSECONDS);
            } catch (ExecutionException | TimeoutException failure) {
                throw new IOException("Command process could not be terminated: " + handle.pid(), failure);
            }
        }
    }

    @SuppressWarnings("ResultOfMethodCallIgnored")
    public static CommandResult waitFor(CommandHandler command) throws IOException, InterruptedException {
        try {
            return command.await();
        } catch (InterruptedException interrupted) {
            Thread.interrupted();
            try {
                command.stop();
            } catch (IOException | InterruptedException cleanup) {
                interrupted.addSuppressed(cleanup);
            } finally {
                Thread.currentThread().interrupt();
            }
            throw interrupted;
        }
    }

    static void cleanup(ProcessResult result) throws IOException, InterruptedException {
        try {
            if (result.child.isAlive()) terminateTree(result.child);
        } finally {
            closeStreams(result.child);
            if (result.inputThread != null) result.inputThread.interrupt();
            if (result.outputThread != null) result.outputThread.interrupt();
            joinIoThreads(result.inputThread, result.outputThread);
        }
    }

    static void joinIoThreads(Thread input, Thread output) throws IOException, InterruptedException {
        long deadline = System.nanoTime() + CLEANUP_TIMEOUT.toNanos();
        for (Thread thread : new Thread[]{input, output}) {
            if (thread == null) continue;
            long remaining = deadline - System.nanoTime();
            if (thread.isAlive() && remaining > 0) thread.join(Duration.ofNanos(remaining));
            if (thread.isAlive()) throw new IOException("Command I/O did not close after process termination");
        }
    }

    public static void closeStreams(Process child) {
        if (child == null) return;
        try {
            child.getInputStream().close();
        } catch (IOException ignored) {
        }
        try {
            child.getErrorStream().close();
        } catch (IOException ignored) {
        }
        try {
            child.getOutputStream().close();
        } catch (IOException ignored) {
        }
    }
}