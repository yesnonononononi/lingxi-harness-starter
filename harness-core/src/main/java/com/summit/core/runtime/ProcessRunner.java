package com.summit.core.runtime;

import lombok.extern.slf4j.Slf4j;

import java.io.BufferedReader;
import java.io.File;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.Charset;
import java.time.Duration;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Shared process-running logic used by {@link WorkspaceBridge} implementations
 * that ultimately spawn a host-side process (the local bridge directly, a
 * sandbox bridge via its sandbox CLI such as {@code docker exec}).
 *
 * <p>Owns the output-buffering budget, the timeout kill switch and the
 * combined stdout/stderr decoding so every bridge behaves identically.</p>
 */
@Slf4j
public final class ProcessRunner {

    private static final long JOIN_SECONDS = 1;

    private ProcessRunner() {
    }

    /**
     * Starts the given command, drains its combined output into a bounded
     * buffer and waits up to {@code timeoutSeconds} before destroying it.
     */
    public static WorkspaceBridge.CommandResult run(List<String> command, String workDir, Charset charset,
                                                    long timeoutSeconds, long maxOutputChars)
            throws IOException, InterruptedException {

        StringBuilder buffer = new StringBuilder();
        AtomicBoolean truncated = new AtomicBoolean(false);

        Process process = runBuilder(command, workDir);

        Thread outputThread = runThread(process, charset, maxOutputChars, buffer, truncated);

        boolean timedOut = threadTimeoutProcess(process, timeoutSeconds, outputThread);

        int exitCode = process.exitValue();
        if (truncated.get()) {
            log.debug("Process output truncated beyond {} chars for command {}", maxOutputChars, command);
        }
        return new WorkspaceBridge.CommandResult(exitCode, buffer.toString(), truncated.get(), timedOut);
    }


    /**
     * Start a process builder with workdir and redirect error stream to stdout
     * @param command the command to execute
     * @param workDir the working directory
     * @return the process
     */
    private static Process runBuilder(List<String> command, String workDir) throws IOException {
        ProcessBuilder processBuilder = new ProcessBuilder(command);
        processBuilder.redirectErrorStream(true);
        if (workDir != null) {
            processBuilder.directory(new File(workDir));
        }
        return processBuilder.start();
    }

    /**
     * Start a thread to read the process output
     * @param process the process to read
     * @param charset the charset to use
     * @param maxOutputChars the maximum number of output characters to buffer
     * @param buffer the buffer to store the output
     * @param truncated the flag to indicate if the output is truncated
     * @return a new thread
     */
    private static Thread runThread(Process process, Charset charset, long maxOutputChars, StringBuilder buffer, AtomicBoolean truncated) {
        return Thread.ofVirtual().start(() -> {
            try (BufferedReader reader = new BufferedReader(
                    new InputStreamReader(process.getInputStream(), charset))) {
                String line;
                while ((line = reader.readLine()) != null) {
                    if (buffer.length() >= maxOutputChars) {
                        truncated.set(true);
                        continue;
                    }
                    buffer.append(line).append("\n");
                }
            } catch (IOException e) {
                log.warn("Failed to read process output: {}", process, e);
                buffer.append("\n[OUTPUT_READ_ERROR] failed to read process output: ")
                        .append(e.getMessage())
                        .append("\n");
            }
        });
    }

    /**
     * Timeout the process and join the output thread
     * @param process the process to read
     * @param timeoutSeconds timeout
     * @param outputThread the output thread
     * @return true if timed out, false otherwise
     */
    private static boolean threadTimeoutProcess(Process process, long timeoutSeconds, Thread outputThread) throws InterruptedException {
        boolean timedOut = false;
        if (!process.waitFor(timeoutSeconds, TimeUnit.SECONDS)) {
            timedOut = true;
            process.destroy();
            if (process.isAlive()) {
                process.destroyForcibly();
            }
            outputThread.interrupt();
            outputThread.join(Duration.of(JOIN_SECONDS, ChronoUnit.SECONDS));
        } else {
            outputThread.join();
        }

        return timedOut;
    }
}
