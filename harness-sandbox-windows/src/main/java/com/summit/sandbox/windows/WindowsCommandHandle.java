package com.summit.sandbox.windows;

import com.sun.jna.platform.win32.WinBase;
import com.sun.jna.platform.win32.WinNT;
import com.sun.jna.ptr.IntByReference;
import com.summit.core.runtime.CommandHandler;
import com.summit.core.runtime.workspace.WorkspaceCommands.CommandResult;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.Charset;
import java.time.Duration;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * {@link CommandHandler} backed by a Windows Job Object and pipe reader thread.
 */
final class WindowsCommandHandle implements CommandHandler {
    private static final int WAIT_TIMEOUT = 0x00000102;

    private final CompletableFuture<CommandResult> completion = new CompletableFuture<>();
    private final ByteArrayOutputStream captured = new ByteArrayOutputStream();
    private final Object captureLock = new Object();
    private final Object stopLock = new Object();
    private final AtomicBoolean cancelled = new AtomicBoolean(false);
    private final Charset outputCharset;
    private final long maxChars;
    private final WinNT.HANDLE jobHandle;
    private final WinNT.HANDLE processHandle;
    private final WinNT.HANDLE threadHandle;
    private final WinNT.HANDLE outRead;

    WindowsCommandHandle(
            WinNT.HANDLE jobHandle,
            WinNT.HANDLE processHandle,
            WinNT.HANDLE threadHandle,
            WinNT.HANDLE outRead,
            Charset outputCharset,
            long maxChars,
            Duration timeout
    ) {
        this.jobHandle = jobHandle;
        this.processHandle = processHandle;
        this.threadHandle = threadHandle;
        this.outRead = outRead;
        this.outputCharset = outputCharset;
        this.maxChars = maxChars;

        Thread readerThread = new Thread(this::drainPipe, "win-sandbox-stdout");
        readerThread.setDaemon(true);
        readerThread.start();

        Thread waiterThread = new Thread(() -> awaitCompletion(readerThread, timeout), "win-sandbox-waiter");
        waiterThread.setDaemon(true);
        waiterThread.start();
    }

    @Override
    public String outputSnapshot() {
        return formatOutput().text();
    }

    @Override
    public boolean isDone() {
        return completion.isDone();
    }

    @Override
    public CommandResult await() throws IOException, InterruptedException {
        try {
            return completion.get();
        } catch (ExecutionException e) {
            Throwable cause = e.getCause();
            if (cause instanceof IOException ioe) {
                throw ioe;
            }
            throw new IOException("Windows sandbox command failed", cause);
        }
    }

    @Override
    public StopResult stop() throws IOException, InterruptedException {
        synchronized (stopLock) {
            if (completion.isDone()) {
                return new StopResult(false, await());
            }
            cancelled.set(true);
            Win32SandboxApi.Kernel32Ext.INSTANCE.TerminateJobObject(jobHandle, 1);
            CommandResult result;
            try {
                result = completion.get(10, TimeUnit.SECONDS);
            } catch (ExecutionException e) {
                Throwable cause = e.getCause();
                if (cause instanceof IOException ioe) {
                    throw ioe;
                }
                throw new IOException("Windows sandbox command failed during stop", cause);
            } catch (TimeoutException e) {
                throw new IOException("Windows sandbox job did not terminate within 10s", e);
            }
            return new StopResult(!result.timedOut(), result);
        }
    }

    private void awaitCompletion(Thread readerThread, Duration timeout) {
        try {
            long timeoutMs = Math.max(1L, timeout.toMillis());
            int waitMs = timeoutMs > Integer.MAX_VALUE ? Integer.MAX_VALUE : (int) timeoutMs;
            int waitResult = Win32SandboxApi.Kernel32Ext.INSTANCE.WaitForSingleObject(processHandle, waitMs);
            boolean timedOut = waitResult == WAIT_TIMEOUT && !cancelled.get();
            if (timedOut) {
                Win32SandboxApi.Kernel32Ext.INSTANCE.TerminateJobObject(jobHandle, 1);
            }
            readerThread.join(5000);
            IntByReference exitCodeRef = new IntByReference(-1);
            Win32SandboxApi.Kernel32Ext.INSTANCE.GetExitCodeProcess(processHandle, exitCodeRef);
            FormattedOutput formatted = formatOutput();
            completion.complete(new CommandResult(exitCodeRef.getValue(), formatted.text(), formatted.truncated(), timedOut));
        } catch (Throwable t) {
            Win32SandboxApi.Kernel32Ext.INSTANCE.TerminateJobObject(jobHandle, 1);
            completion.completeExceptionally(t);
        } finally {
            closeQuietly(threadHandle);
            closeQuietly(processHandle);
            closeQuietly(outRead);
            closeQuietly(jobHandle);
        }
    }

    private void drainPipe() {
        byte[] buffer = new byte[4096];
        IntByReference readRef = new IntByReference();
        while (Win32SandboxApi.Kernel32Ext.INSTANCE.ReadFile(outRead, buffer, buffer.length, readRef, null)) {
            int read = readRef.getValue();
            if (read <= 0) {
                break;
            }
            synchronized (captureLock) {
                captured.write(buffer, 0, read);
            }
        }
    }

    private FormattedOutput formatOutput() {
        String raw;
        synchronized (captureLock) {
            raw = captured.toString(outputCharset);
        }
        if (maxChars > 0 && raw.length() > maxChars) {
            return new FormattedOutput(raw.substring(0, (int) maxChars), true);
        }
        return new FormattedOutput(raw, false);
    }

    private static void closeQuietly(WinNT.HANDLE handle) {
        if (handle != null && !WinBase.INVALID_HANDLE_VALUE.equals(handle)) {
            Win32SandboxApi.Kernel32Ext.INSTANCE.CloseHandle(handle);
        }
    }

    private record FormattedOutput(String text, boolean truncated) {
    }
}
