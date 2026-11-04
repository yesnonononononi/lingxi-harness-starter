package com.summit.core.runtime;

import com.summit.core.runtime.workspace.WorkspaceCommands.CommandResult;
import org.junit.jupiter.api.Test;

import java.io.InterruptedIOException;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Pins the contract that a closed subprocess pipe is end of file, not a read failure.
 *
 * <p>Linux CI reproduced the defect these cases harden: unix {@code Process.destroy} closes the
 * subprocess pipes (stdin/stdout/stderr) right after it kills the child, so the output reader can
 * wake up on an already closed stream. Treating that as an I/O failure turns a normal shutdown into
 * {@code Failed to read command output}, which is what
 * {@code ProcessRunnerTest#rejectedInputStopsTheProcessAndDoesNotLeakRunnerState} hit on Linux.
 */
class ProcessOutputInputStreamTest {
    /** stdout payload of a child that refuses stdin, written before it closes its own stdin. */
    private static final String REJECTED_INPUT = "input rejected";

    /** Exit code the scripted child reports once it is terminated. */
    private static final int EXIT_CODE = 137;


    @Test
    void closeIsEndOfFileForReadAndAvailable() throws Exception {
        ClosablePipe pipe = new ClosablePipe();
        pipe.emit(REJECTED_INPUT.getBytes(StandardCharsets.UTF_8));
        ProcessOutputInputStream output = new ProcessOutputInputStream(new ScriptedProcess(pipe));
        byte[] buffer = new byte[64];
        assertEquals(REJECTED_INPUT.length(), output.read(buffer, 0, buffer.length));
        output.close();
        assertEquals(-1, output.read(buffer, 0, buffer.length), "a closed stream reports end of file");
        assertEquals(0, output.available(), "a closed stream has nothing pending");
        assertEquals(-1, output.read(), "the single byte read reports end of file too");
        output.close();
    }

    @Test
    void pipeClosedUnderneathTheReaderIsEndOfFileAndKeepsTheOutput() throws Exception {
        ClosablePipe pipe = new ClosablePipe();
        pipe.emit(REJECTED_INPUT.getBytes(StandardCharsets.UTF_8));
        ScriptedProcess child = new ScriptedProcess(pipe);
        ProcessResult result = new ProcessResult(child);
        result.begin(StandardCharsets.UTF_8, new byte[8 * 1024 * 1024], 4096, 10, TimeUnit.SECONDS,
                System.nanoTime(), null, () -> { });
        CommandResult completed = result.await();
        assertTrue(child.destroyed.get(), "a rejected stdin must terminate the child");
        assertFalse(completed.timedOut(), "an input failure must not wait for the execution timeout");
        assertEquals(EXIT_CODE, completed.exitCode());
        assertFalse(completed.truncated());
        assertEquals(REJECTED_INPUT, completed.output(),
                "output written before the pipe closed must survive the shutdown");
    }

    @Test
    void genuineReadFailureIsStillReported() {
        ClosablePipe pipe = new ClosablePipe();
        pipe.failWith(new IOException("Input/output error"));
        ProcessOutputInputStream output = new ProcessOutputInputStream(new ScriptedProcess(pipe));
        IOException failure = assertThrows(IOException.class, () -> output.read(new byte[8], 0, 8));
        assertEquals("Input/output error", failure.getMessage());
        assertThrows(IOException.class, output::available);
    }

    /**
     * Stands in for the JDK subprocess pipe: it keeps the bytes the child wrote until they are read,
     * and afterwards behaves like a closed {@code BufferedInputStream} whose every call fails with
     * {@code IOException("Stream closed")} - the very signal {@code getInIfOpen} raises in production.
     */
    private static final class ClosablePipe extends InputStream {
        private final Object lock = new Object();
        private byte[] written = new byte[0];
        private int count;
        private int position;
        private boolean sourceClosed;
        private IOException scheduledFailure;

        void emit(byte[] bytes) {
            synchronized (lock) {
                byte[] grown = new byte[count + bytes.length];
                System.arraycopy(written, 0, grown, 0, count);
                System.arraycopy(bytes, 0, grown, count, bytes.length);
                written = grown;
                count += bytes.length;
            }
        }

        void failWith(IOException failure) {
            synchronized (lock) { scheduledFailure = Objects.requireNonNull(failure, "failure must not be null"); }
        }

        /** Mirrors unix {@code ProcessImpl.destroy}: the pipe closes while the reader may poll it. */
        void closeUnderneath() {
            synchronized (lock) { sourceClosed = true; }
        }

        boolean isDrained() {
            synchronized (lock) { return position >= count; }
        }

        @Override
        public int available() throws IOException {
            synchronized (lock) {
                checkOpen();
                return count - position;
            }
        }

        @Override
        public int read(byte[] bytes, int offset, int length) throws IOException {
            Objects.checkFromIndexSize(offset, length, bytes.length);
            synchronized (lock) {
                checkOpen();
                if (length == 0) return 0;
                int available = count - position;
                if (available == 0) return -1;
                int copied = Math.min(length, available);
                System.arraycopy(written, position, bytes, offset, copied);
                position += copied;
                return copied;
            }
        }

        @Override
        public int read() throws IOException {
            byte[] single = new byte[1];
            int copied = read(single, 0, 1);
            return copied == -1 ? -1 : Byte.toUnsignedInt(single[0]);
        }

        @Override
        public void close() {
            closeUnderneath();
        }

        private void checkOpen() throws IOException {
            if (sourceClosed) throw new IOException("Stream closed");
            if (scheduledFailure != null) throw scheduledFailure;
        }
    }

    /**
     * Stands in for a child that rejects its stdin. stdout holds the payload until the reader takes it,
     * the stdin write fails with "Broken pipe" only after that payload was read (the real ordering),
     * and termination - through the handle or through {@code destroyForcibly} - kills the child and
     * closes the stdout pipe like a real closer does.
     */
    private static final class ScriptedProcess extends Process {
        private static final long DRAIN_DEADLINE_NANOS = TimeUnit.SECONDS.toNanos(5);

        private static final long SCRIPTED_PID = -1L;

        private final ClosablePipe stdout;
        private final CountDownLatch exited = new CountDownLatch(1);
        private final AtomicBoolean destroyed = new AtomicBoolean();
        private final CompletableFuture<ProcessHandle> exitFuture = new CompletableFuture<>();
        private final ScriptedHandle handle = new ScriptedHandle();
        private final OutputStream stdin = new OutputStream() {
            @Override
            public void write(int value) throws IOException {
                write(new byte[]{(byte) value}, 0, 1);
            }

            @Override
            public void write(byte[] bytes, int offset, int length) throws IOException {
                Objects.checkFromIndexSize(offset, length, bytes.length);
                awaitDrainedPayload();
                throw new IOException("Broken pipe");
            }

            @Override
            public void close() {
                // The child already closed its own stdin, so closing the parent side changes nothing.
            }
        };
        private volatile int exitCode = -1;
        private volatile boolean exitedState;

        ScriptedProcess(ClosablePipe stdout) {
            this.stdout = Objects.requireNonNull(stdout, "stdout pipe must not be null");
        }

        private void awaitDrainedPayload() throws IOException {
            long deadline = System.nanoTime() + DRAIN_DEADLINE_NANOS;
            try {
                while (!stdout.isDrained() && System.nanoTime() < deadline) Thread.sleep(1);
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
                throw new InterruptedIOException("stdin handshake interrupted");
            }
            // Stdin may only fail after the rejection diagnostic was read, so the output cannot be lost.
            if (!stdout.isDrained()) throw new IOException("stdin handshake timed out");
        }

        @Override
        public OutputStream getOutputStream() {
            return stdin;
        }

        @Override
        public InputStream getInputStream() {
            return stdout;
        }

        @Override
        public InputStream getErrorStream() {
            return InputStream.nullInputStream();
        }

        @Override
        public boolean waitFor(long timeout, TimeUnit unit) throws InterruptedException {
            Objects.requireNonNull(unit, "time unit must not be null");
            return exited.await(Math.max(0, unit.toNanos(timeout)), TimeUnit.NANOSECONDS);
        }

        @Override
        public int waitFor() throws InterruptedException {
            exited.await();
            return exitCode;
        }

        @Override
        public int exitValue() {
            if (!exitedState) throw new IllegalThreadStateException("process has not exited");
            return exitCode;
        }

        @Override
        public boolean isAlive() {
            return !exitedState;
        }

        @Override
        public void destroy() {
            handle.destroy();
        }

        @Override
        public Process destroyForcibly() {
            handle.destroyForcibly();
            return this;
        }

        @Override
        public ProcessHandle toHandle() {
            return handle;
        }

        @Override
        public Stream<ProcessHandle> descendants() {
            return Stream.empty();
        }

        /**
         * Terminates the scripted child through the same entry point a real closer uses.
         *
         * @return {@code true} when this call terminated the child
         */
        private boolean terminate() {
            if (!destroyed.compareAndSet(false, true)) return false;
            // A real closer (unix ProcessImpl.destroy) closes stdin/stdout/stderr right after the kill,
            // so the reader may still be polling the pipe when it disappears: that close must read as
            // end of file. Closing before publishing the exit forces that race every single run.
            stdout.closeUnderneath();
            exitCode = EXIT_CODE;
            exitedState = true;
            exited.countDown();
            exitFuture.complete(handle);
            return true;
        }

        /** Handle of the scripted child, so termination enters through the production entry point. */
        private final class ScriptedHandle implements ProcessHandle {
            @Override
            public long pid() {
                return SCRIPTED_PID;
            }

            @Override
            public ProcessHandle.Info info() {
                // A scripted child has no OS process; termination never inspects process information.
                return ProcessHandle.current().info();
            }

            @Override
            public Optional<ProcessHandle> parent() {
                return Optional.empty();
            }

            @Override
            public Stream<ProcessHandle> children() {
                return Stream.empty();
            }

            @Override
            public Stream<ProcessHandle> descendants() {
                return Stream.empty();
            }

            @Override
            public boolean isAlive() {
                return !exitedState;
            }

            @Override
            public boolean supportsNormalTermination() {
                return false;
            }

            @Override
            public boolean destroy() {
                return terminate();
            }

            @Override
            public boolean destroyForcibly() {
                return terminate();
            }

            @Override
            public CompletableFuture<ProcessHandle> onExit() {
                return exitFuture;
            }

            @Override
            public int compareTo(ProcessHandle other) {
                return other == null ? 1 : Long.compare(pid(), other.pid());
            }

            @Override
            public boolean equals(Object other) {
                return this == other;
            }

            @Override
            public int hashCode() {
                return System.identityHashCode(this);
            }

            @Override
            public String toString() {
                return "ScriptedHandle[" + pid() + "]";
            }
        }
    }
}
