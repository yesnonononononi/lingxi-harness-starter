package com.summit.core.runtime;

import java.io.IOException;
import java.io.InputStream;
import java.io.InterruptedIOException;
import java.util.Objects;

/**
 * Drains available output without waiting for pipe handles inherited by detached descendants.
 *
 * <p>Closing this stream means end of file, never a read failure: a reader that is already inside
 * {@link #read} must observe {@code -1}, and {@link #available()} must report {@code 0}, instead of
 * an {@code IOException}. That matters because the JDK closes subprocess pipes behind our back: unix
 * {@code ProcessImpl.destroy} closes stdin/stdout/stderr right after it kills the child, so a
 * terminating command can close this pipe while the reader is still polling it. Turning that orderly
 * shutdown into "Failed to read command output" was a Linux-only production defect.
 *
 * <p>Every other I/O failure keeps propagating, so a genuine pipe fault is never swallowed.
 */
final class ProcessOutputInputStream extends InputStream {
    /** Message the JDK throws when a stream is used after it was closed. */
    private static final String CLOSED_MESSAGE = "Stream closed";

    /** Poll interval used while the child is alive but has no pending output. */
    private static final long POLL_MILLIS = 10L;

    private final Process process;
    private final InputStream source;

    /** Set before the delegate is closed, so a concurrent reader observes end of file. */
    private volatile boolean closed;

    ProcessOutputInputStream(Process process) {
        this.process = Objects.requireNonNull(process, "process must not be null");
        this.source = Objects.requireNonNull(process.getInputStream(), "process output stream must not be null");
    }

    @Override
    public int read(byte[] bytes, int offset, int length) throws IOException {
        Objects.checkFromIndexSize(offset, length, bytes.length);
        if (length == 0) return 0;
        if (closed) return -1;
        while (true) {
            int available = pendingOrEndOfFile();
            if (available < 0) return -1;
            if (available > 0) return readAvailable(bytes, offset, Math.min(length, available));
            if (!process.isAlive()) {
                // Exit may race the first availability check; drain bytes written just before exit.
                available = pendingOrEndOfFile();
                if (available < 0) return -1;
                return available == 0 ? -1 : readAvailable(bytes, offset, Math.min(length, available));
            }
            sleepBeforeNextPoll();
        }
    }

    @Override
    public int read() throws IOException {
        byte[] single = new byte[1];
        return read(single, 0, 1) == -1 ? -1 : Byte.toUnsignedInt(single[0]);
    }

    @Override
    public int available() throws IOException {
        int pending = pendingOrEndOfFile();
        return pending < 0 ? 0 : pending;
    }

    @Override
    public void close() throws IOException {
        if (closed) return;
        closed = true;
        source.close();
    }

    /**
     * Returns the number of buffered bytes, or {@code -1} when the pipe is closed.
     *
     * @return pending byte count, or {@code -1} for an orderly end of file
     * @throws IOException when the pipe reports a fault other than a closed stream
     */
    private int pendingOrEndOfFile() throws IOException {
        if (closed) return -1;
        try {
            return source.available();
        } catch (IOException failure) {
            return endOfFileOrRethrow(failure);
        }
    }

    /**
     * Reads up to {@code length} buffered bytes.
     *
     * @return bytes transferred, or {@code -1} when the pipe closed during the read
     * @throws IOException when the pipe reports a fault other than a closed stream
     */
    private int readAvailable(byte[] bytes, int offset, int length) throws IOException {
        if (closed) return -1;
        try {
            return source.read(bytes, offset, length);
        } catch (IOException failure) {
            return endOfFileOrRethrow(failure);
        }
    }

    /**
     * Converts the closed-stream signal into end of file and rethrows anything else.
     *
     * @return always {@code -1}, the end-of-file marker
     * @throws IOException the original failure when it is not the closed-stream signal
     */
    private int endOfFileOrRethrow(IOException failure) throws IOException {
        if (!isClosedSignal(failure)) throw failure;
        closed = true;
        return -1;
    }

    /**
     * Tells "the pipe was closed on purpose" apart from a real I/O fault.
     *
     * <p>Boundary: a subprocess pipe only reports the closed stream as
     * {@code IOException("Stream closed")} from {@code BufferedInputStream.getInIfOpen}. It means
     * somebody closed the handle deliberately - our own {@link #close()}, the JDK termination path or
     * the coordinator cleanup - so it is an orderly end of file. Every other failure (broken pipe,
     * I/O error, interrupted read) is a genuine fault and still reaches the caller.
     *
     * @param failure failure raised by the delegate stream
     * @return {@code true} when the failure only reports an already closed stream
     */
    private static boolean isClosedSignal(IOException failure) {
        return CLOSED_MESSAGE.equalsIgnoreCase(failure.getMessage());
    }

    /**
     * Waits one poll interval before re-checking the pipe.
     *
     * @throws InterruptedIOException when the reader is interrupted, with the flag restored
     */
    private static void sleepBeforeNextPoll() throws InterruptedIOException {
        try {
            Thread.sleep(POLL_MILLIS);
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            throw new InterruptedIOException("Command output reading interrupted");
        }
    }
}
