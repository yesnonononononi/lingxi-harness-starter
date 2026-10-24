package com.summit.core.runtime;

import com.summit.core.runtime.workspace.WorkspaceCommands.CommandResult;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.io.Reader;
import java.nio.charset.Charset;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

/** Owns process I/O and one cached completion, independently of any waiting caller. */
public final class ProcessResult implements CommandHandler {
    final Process child;
    final StringBuffer output = new StringBuffer();
    final AtomicBoolean truncated = new AtomicBoolean();
    final AtomicBoolean stopping = new AtomicBoolean();
    final AtomicReference<IOException> readFailure = new AtomicReference<>();
    final AtomicReference<IOException> writeFailure = new AtomicReference<>();
    Thread inputThread;
    Thread outputThread;
    private final CompletableFuture<CommandResult> completion = new CompletableFuture<>();
    private final Object stopLock = new Object();

    ProcessResult(Process child) { this.child = child; }

    void begin(Charset charset, byte[] stdin, long maxChars, long timeout, TimeUnit unit,
               long started, ProcessOutputListener listener, Runnable release) {
        outputThread = Thread.ofVirtual().start(() -> {
            try (Reader reader = new InputStreamReader(child.getInputStream(), charset)) {
                char[] chars = new char[4096];
                int count;
                while ((count = reader.read(chars)) != -1) {
                    if (listener != null) listener.onOutput(new String(chars, 0, count));
                    int keep = (int) Math.min(count, maxChars - output.length());
                    // 截断点不能留下半个代理对（emoji 占两个 char），否则模型看到的是坏字符。
                    if (keep > 0 && Character.isHighSurrogate(chars[keep - 1])) keep--;
                    if (keep > 0) output.append(chars, 0, keep);
                    if (keep < count) truncated.set(true);
                }
            } catch (IOException failure) {
                readFailure.set(failure);
                ProcessCoordinator.requestTermination(child);
            } catch (RuntimeException failure) {
                readFailure.set(new IOException("Command output listener failed", failure));
                ProcessCoordinator.requestTermination(child);
            }
        });
        inputThread = Thread.ofVirtual().start(() -> {
            // EOF is required even when the command receives no input.
            try (OutputStream input = child.getOutputStream()) { input.write(stdin); }
            catch (IOException failure) {
                writeFailure.set(failure);
                ProcessCoordinator.requestTermination(child);
            }
        });
        Thread.ofVirtual().start(() -> {
            CommandResult result = null;
            Throwable failure = null;
            try { result = ProcessCoordinator.postProcess(this, unit, timeout, started); }
            catch (Throwable error) { failure = error; }
            try { ProcessCoordinator.cleanup(this); }
            catch (Throwable error) {
                if (failure == null) failure = error;
                else failure.addSuppressed(error);
            } finally { release.run(); }
            if (failure == null) completion.complete(result);
            else completion.completeExceptionally(failure);
        });
    }

    @Override public String outputSnapshot() { return output.toString(); }
    @Override public boolean isDone() { return completion.isDone(); }

    @Override public CommandResult await() throws IOException, InterruptedException {
        try { return completion.get(); }
        catch (ExecutionException failure) {
            if (failure.getCause() instanceof IOException error) throw error;
            throw new IOException("Command execution failed", failure.getCause());
        }
    }

    boolean requestStop() {
        if (completion.isDone() || !child.isAlive()) return false;
        stopping.set(true);
        ProcessCoordinator.requestTermination(child);
        return true;
    }

    @Override public StopResult stop() throws IOException, InterruptedException {
        synchronized (stopLock) {
            if (completion.isDone()) return new StopResult(false, await());
            boolean active = child.isAlive();
            if (active) {
                stopping.set(true);
                ProcessCoordinator.terminateTree(child);
            }
            CommandResult result = await();
            return new StopResult(active && result.exitCode() != 0 && !result.timedOut(), result);
        }
    }
}