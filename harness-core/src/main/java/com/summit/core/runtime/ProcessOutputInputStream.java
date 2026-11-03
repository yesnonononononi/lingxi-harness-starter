package com.summit.core.runtime;

import java.io.IOException;
import java.io.InputStream;
import java.io.InterruptedIOException;
import java.util.Objects;

/** Drains available output without waiting for pipe handles inherited by detached descendants. */
final class ProcessOutputInputStream extends InputStream {
    private final Process process;
    private final InputStream source;

    ProcessOutputInputStream(Process process) {
        this.process = process;
        this.source = process.getInputStream();
    }

    @Override
    public int read(byte[] bytes, int offset, int length) throws IOException {
        Objects.checkFromIndexSize(offset, length, bytes.length);
        if (length == 0) return 0;
        while (true) {
            int available = source.available();
            if (available > 0) return source.read(bytes, offset, Math.min(length, available));
            if (!process.isAlive()) {
                // Exit may race the first availability check; drain bytes written just before exit.
                available = source.available();
                return available == 0 ? -1 : source.read(bytes, offset, Math.min(length, available));
            }
            try {
                Thread.sleep(10);
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
                throw new InterruptedIOException("Command output reading interrupted");
            }
        }
    }

    @Override
    public int read() throws IOException {
        byte[] single = new byte[1];
        return read(single, 0, 1) == -1 ? -1 : Byte.toUnsignedInt(single[0]);
    }

    @Override
    public int available() throws IOException {
        return source.available();
    }

    @Override
    public void close() throws IOException {
        source.close();
    }
}
