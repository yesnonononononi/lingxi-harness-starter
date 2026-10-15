package com.summit.core.runtime;

import com.summit.core.runtime.workspace.WorkspaceBridge.CommandResult;
import com.summit.core.runtime.workspace.LocalWorkspaceBridge;
import com.summit.core.runtime.workspace.ShellType;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Base64;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.*;

class ProcessRunnerTest {
    @TempDir Path temporary;

    @Test
    void successfulCommandKeepsExitZeroAndLiteralArguments() throws Exception {
        String literal = "spaces & symbols ; $(literal)";
        CommandResult result = new ProcessRunner().run(command("echo", literal), 5, TimeUnit.SECONDS);
        assertEquals(0, result.exitCode());
        assertTrue(result.isSuccess());
        assertEquals(literal, result.output());
    }

    @Test
    void nonzeroExitAndStderrArePreserved() throws Exception {
        CommandResult result = new ProcessRunner().run(command("fail"), 5, TimeUnit.SECONDS);
        assertEquals(7, result.exitCode());
        assertFalse(result.isSuccess());
        assertEquals("failure diagnostic", result.output());
    }

    @Test
    void outputLargerThanAPipeBufferDoesNotDeadlock() throws Exception {
        CommandResult result = new ProcessRunner().run(command("large"), 5, TimeUnit.SECONDS);
        assertTrue(result.isSuccess());
        assertEquals("x".repeat(256 * 1024), result.output());
    }

    @Test
    void stdinBytesAreDeliveredUnchangedAndClosedWithEof() throws Exception {
        byte[] input = "中文\r\nlast line\u0000".getBytes(StandardCharsets.UTF_8);
        CommandResult result = new ProcessRunner().run(command("stdin"), input, 10, TimeUnit.SECONDS);
        assertTrue(result.isSuccess(), result.output());
        assertEquals(Base64.getEncoder().encodeToString(input), result.output());
    }

    @Test
    void omittedStdinStillClosesTheInputPipe() throws Exception {
        CommandResult result = new ProcessRunner().run(command("stdin"), 10, TimeUnit.SECONDS);
        assertTrue(result.isSuccess(), result.output());
        assertEquals("", result.output());
    }

    @Test
    void inputAndOutputLargerThanPipeBuffersCanFlowAtTheSameTime() throws Exception {
        byte[] input = new byte[2 * 1024 * 1024];
        Arrays.fill(input, (byte) 'z');
        CommandResult result = assertTimeoutPreemptively(Duration.ofSeconds(12),
                () -> new ProcessRunner().run(command("duplex"), input, 10, TimeUnit.SECONDS));
        assertTrue(result.isSuccess(), result.output());
        assertEquals("x".repeat(256 * 1024) + ":" + input.length, result.output());
    }

    @Test
    void aChildThatNeverReadsLargeStdinStillTimesOutAndRunnerCanBeReused() throws Exception {
        Path pidFile = temporary.resolve("blocked-input.pid");
        ProcessRunner runner = new ProcessRunner();
        CommandResult result = assertTimeoutPreemptively(Duration.ofSeconds(9),
                () -> runner.run(command("sleep", pidFile.toString()), new byte[8 * 1024 * 1024],
                        3, TimeUnit.SECONDS));
        assertTrue(result.timedOut());
        assertEquals(-1, result.exitCode());
        assertProcessGone(pidFile);
        assertTrue(runner.run(command("echo", "again"), 10, TimeUnit.SECONDS).isSuccess());
    }

    @Test
    void rejectedInputStopsTheProcessAndDoesNotLeakRunnerState() throws Exception {
        Path pidFile = temporary.resolve("rejected-input.pid");
        ProcessRunner runner = new ProcessRunner();
        CommandResult result = assertTimeoutPreemptively(Duration.ofSeconds(8),
                () -> runner.run(command("reject-input", pidFile.toString()), new byte[8 * 1024 * 1024],
                        10, TimeUnit.SECONDS));
        assertFalse(result.isSuccess());
        assertFalse(result.timedOut(), "an input failure must not leave the child waiting for the execution timeout");
        assertTrue(result.output().contains("input rejected"), result.output());
        assertProcessGone(pidFile);
        assertTrue(runner.run(command("echo", "again"), 10, TimeUnit.SECONDS).isSuccess());
    }

    @Test
    void returnedOutputRespectsTheExistingCharacterLimit() throws Exception {
        CommandResult result = new ProcessRunner().run(command("echo", "abcdef"),
                new byte[0], 10, TimeUnit.SECONDS, 3);
        assertTrue(result.isSuccess());
        assertEquals("abc", result.output());
        assertTrue(result.truncated());
    }

    @Test
    void staticEntryPreservesTheRequestedCharsetAndOriginalLineEndings() throws Exception {
        String text = "中文\r\nno final newline";
        CommandResult result = ProcessRunner.run(List.of(command("encoded", "UTF-16LE", text)),
                null, StandardCharsets.UTF_16LE, 10, 4096);
        assertTrue(result.isSuccess(), result.output());
        assertEquals(text, result.output());
        assertFalse(result.truncated());
    }

    @Test
    void staticEntryAcceptsStdinAndAWorkingDirectoryTogether() throws Exception {
        byte[] input = "working directory and stdin".getBytes(StandardCharsets.UTF_8);
        CommandResult result = ProcessRunner.run(List.of(command("cwd-stdin")), temporary.toString(),
                StandardCharsets.UTF_8, input, 10, TimeUnit.SECONDS, 4096);
        assertTrue(result.isSuccess(), result.output());
        assertEquals(temporary.toAbsolutePath() + "|" + Base64.getEncoder().encodeToString(input), result.output());
    }

    @Test
    void localBridgeBoundsALongLineAndStillDrainsTheWholePipe() throws Exception {
        CommandResult result = localBridge().execute(shellCommand(command("large")), temporary.toString(),
                StandardCharsets.UTF_8, 10, TimeUnit.SECONDS, 17);
        assertTrue(result.isSuccess(), result.output());
        assertEquals("x".repeat(17), result.output());
        assertTrue(result.truncated());
    }

    @Test
    void zeroOutputBudgetDiscardsOutputAndReportsTruncation() throws Exception {
        CommandResult result = ProcessRunner.run(List.of(command("echo", "discarded")),
                null, StandardCharsets.UTF_8, 10, TimeUnit.SECONDS, 0);
        assertTrue(result.isSuccess());
        assertEquals("", result.output());
        assertTrue(result.truncated());
    }

    @Test
    void localBridgeHonorsMillisecondsInsteadOfTreatingThemAsSeconds() throws Exception {
        Path pidFile = temporary.resolve("milliseconds.pid");
        CommandResult result = assertTimeoutPreemptively(Duration.ofSeconds(7),
                () -> localBridge().execute(shellCommand(command("sleep", pidFile.toString())),
                        temporary.toString(), StandardCharsets.UTF_8, 1000, TimeUnit.MILLISECONDS, 4096));
        assertTrue(result.timedOut());
        assertEquals(-1, result.exitCode());
        assertProcessGone(pidFile);
    }

    @Test
    void staticEntryPropagatesInterruptionAfterStoppingTheProcess() throws Exception {
        Path pidFile = temporary.resolve("static-interrupted.pid");
        AtomicReference<Exception> failure = new AtomicReference<>();
        AtomicBoolean interrupted = new AtomicBoolean();
        CountDownLatch finished = new CountDownLatch(1);
        Thread worker = Thread.ofPlatform().start(() -> {
            try {
                ProcessRunner.run(List.of(command("sleep", pidFile.toString())),
                        null, StandardCharsets.UTF_8, 10, 4096);
            } catch (IOException | InterruptedException e) {
                failure.set(e);
                interrupted.set(Thread.currentThread().isInterrupted());
            } finally {
                finished.countDown();
            }
        });
        try {
            awaitFile(pidFile);
            worker.interrupt();
            assertTrue(finished.await(5, TimeUnit.SECONDS));
            assertInstanceOf(InterruptedException.class, failure.get());
            assertTrue(interrupted.get());
            long pid = Long.parseLong(Files.readString(pidFile));
            ProcessHandle.of(pid).ifPresent(handle -> {
                try { handle.onExit().get(3, TimeUnit.SECONDS); }
                catch (Exception e) { fail(e); }
            });
            assertProcessGone(pidFile);
        } finally {
            worker.interrupt();
        }
    }

    @Test
    void stdinOverloadsValidateArgumentsBeforeLaunching() throws Exception {
        Path marker = temporary.resolve("invalid-stdin.pid");
        ProcessRunner runner = new ProcessRunner();
        assertThrows(NullPointerException.class,
                () -> runner.run(command("sleep", marker.toString()), null, 5, TimeUnit.SECONDS));
        assertThrows(IllegalArgumentException.class,
                () -> runner.run(command("sleep", marker.toString()), new byte[0], 0, TimeUnit.SECONDS));
        assertThrows(IllegalArgumentException.class,
                () -> runner.run(command("sleep", marker.toString()), new byte[0], 5, null));
        assertThrows(IllegalArgumentException.class,
                () -> runner.run(command("sleep", marker.toString()), new byte[0], 5, TimeUnit.SECONDS, -1));
        assertFalse(Files.exists(marker));
        assertFalse(runner.cancel());
    }

    @Test
    void silentCommandTimesOutAndItsProcessIsGone() throws Exception {
        Path pidFile = temporary.resolve("silent.pid");
        CommandResult result = assertTimeoutPreemptively(Duration.ofSeconds(8),
                () -> new ProcessRunner().run(command("sleep", pidFile.toString()), 2, TimeUnit.SECONDS));
        assertEquals(-1, result.exitCode());
        assertEquals("", result.output());
        assertProcessGone(pidFile);
    }

    @Test
    void timeoutTerminatesTheSpawnedChildAsWellAsItsParent() throws Exception {
        Path parentFile = temporary.resolve("parent.pid");
        Path childFile = temporary.resolve("child.pid");
        CommandResult result = assertTimeoutPreemptively(Duration.ofSeconds(9),
                () -> new ProcessRunner().run(command("tree", parentFile.toString(), childFile.toString()),
                        3, TimeUnit.SECONDS));
        assertEquals(-1, result.exitCode());
        assertProcessGone(parentFile);
        assertProcessGone(childFile);
    }

    @Test
    void invalidTimeoutDoesNotLaunchTheCommand() throws Exception {
        Path marker = temporary.resolve("should-not-exist");
        ProcessRunner runner = new ProcessRunner();
        assertThrows(IllegalArgumentException.class,
                () -> runner.run(command("sleep", marker.toString()), 0, TimeUnit.SECONDS));
        assertThrows(IllegalArgumentException.class,
                () -> runner.run(command("sleep", marker.toString()), 1, null));
        assertFalse(Files.exists(marker));
        assertFalse(runner.cancel());
        assertTrue(runner.run(command("echo", "reusable")).isSuccess());
    }

    @Test
    void cancelStopsAnActiveCommandAndAllowsReuse() throws Exception {
        Path pidFile = temporary.resolve("cancel.pid");
        ProcessRunner runner = new ProcessRunner();
        try (ExecutorService executor = Executors.newSingleThreadExecutor()) {
            // This test exercises cancellation, without a competing execution timeout.
            Future<CommandResult> result = executor.submit(() -> runner.run(command("sleep", pidFile.toString())));
            try {
                awaitFile(pidFile);
                assertTrue(runner.cancel());
                assertFalse(result.get(5, TimeUnit.SECONDS).isSuccess());
                assertProcessGone(pidFile);
                assertFalse(runner.cancel());
                assertTrue(runner.run(command("echo", "again")).isSuccess());
            } finally {
                runner.cancel();
            }
        }
    }

    @Test
    void interruptionStopsTheProcessAndPreservesTheInterruptFlag() throws Exception {
        Path pidFile = temporary.resolve("interrupted.pid");
        ProcessRunner runner = new ProcessRunner();
        AtomicReference<IOException> failure = new AtomicReference<>();
        AtomicBoolean interrupted = new AtomicBoolean();
        CountDownLatch finished = new CountDownLatch(1);
        Thread worker = Thread.ofPlatform().start(() -> {
            try {
                runner.run(command("sleep", pidFile.toString()), 10, TimeUnit.SECONDS);
            } catch (IOException e) {
                failure.set(e);
                interrupted.set(Thread.currentThread().isInterrupted());
            } finally {
                finished.countDown();
            }
        });
        try {
            awaitFile(pidFile);
            worker.interrupt();
            assertTrue(finished.await(5, TimeUnit.SECONDS));
            assertNotNull(failure.get());
            assertTrue(interrupted.get());
            long pid = Long.parseLong(Files.readString(pidFile));
            ProcessHandle.of(pid).ifPresent(handle -> {
                try { handle.onExit().get(3, TimeUnit.SECONDS); }
                catch (Exception e) { fail(e); }
            });
            assertProcessGone(pidFile);
        } finally {
            runner.cancel();
        }
    }

    private static void assertProcessGone(Path file) throws IOException {
        assertTrue(Files.isRegularFile(file), "child must have started before checking termination");
        long pid = Long.parseLong(Files.readString(file));
        assertFalse(ProcessHandle.of(pid).map(ProcessHandle::isAlive).orElse(false), "process " + pid + " is still alive");
    }

    private static void awaitFile(Path file) throws Exception {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
        while (!Files.isRegularFile(file) && System.nanoTime() < deadline) {
            Thread.sleep(20);
        }
        assertTrue(Files.isRegularFile(file), "process did not start");
    }

    private static LocalWorkspaceBridge localBridge() {
        return new LocalWorkspaceBridge(System.getProperty("os.name").toLowerCase().contains("win")
                ? ShellType.CMD : ShellType.SH);
    }

    private static String shellCommand(String[] arguments) {
        boolean windows = System.getProperty("os.name").toLowerCase().contains("win");
        return Arrays.stream(arguments)
                .map(argument -> windows ? "\"" + argument + "\"" : "'" + argument.replace("'", "'\"'\"'") + "'")
                .collect(Collectors.joining(" "));
    }

    private static String[] command(String... arguments) {
        List<String> command = new ArrayList<>();
        String executable = System.getProperty("os.name").toLowerCase().contains("win") ? "java.exe" : "java";
        command.add(Path.of(System.getProperty("java.home"), "bin", executable).toString());
        // 子 JVM 的 stdout/stderr 编码随宿主默认值漂移（runner 上是 cp1252），会让非 ASCII 探针输出
        // 与父进程按 UTF-8 的读取错位；这里固定成 UTF-8，用例才与宿主编码无关。
        command.add("-Dstdout.encoding=UTF-8");
        command.add("-Dstderr.encoding=UTF-8");
        command.add("-cp");
        try {
            command.add(Path.of(Probe.class.getProtectionDomain().getCodeSource().getLocation().toURI()).toString());
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
        command.add(Probe.class.getName());
        command.addAll(Arrays.asList(arguments));
        return command.toArray(String[]::new);
    }

    /** Real child processes exercise exit codes, blocked pipes and process-tree cleanup. */
    public static class Probe {
        public static void main(String[] arguments) throws Exception {
            switch (arguments[0]) {
                case "echo" -> System.out.print(arguments[1]);
                case "fail" -> { System.err.print("failure diagnostic"); System.exit(7); }
                case "large" -> System.out.print("x".repeat(256 * 1024));
                case "stdin" -> System.out.print(Base64.getEncoder().encodeToString(System.in.readAllBytes()));
                case "encoded" -> {
                    // System.out 的 write(byte[]) 会走 stdout.encoding 编码器（JDK 21），不同宿主默认编码
                    // 会把 UTF-16LE 字节转码坏；这里必须写到原始字节流，测试才与宿主编码无关。
                    try (var rawOut = new java.io.FileOutputStream(java.io.FileDescriptor.out)) {
                        rawOut.write(arguments[2].getBytes(Charset.forName(arguments[1])));
                    }
                }
                case "cwd-stdin" -> System.out.print(System.getProperty("user.dir") + "|"
                        + Base64.getEncoder().encodeToString(System.in.readAllBytes()));
                case "duplex" -> {
                    // Filling stdout before consuming stdin catches sequential pipe handling.
                    System.out.print("x".repeat(256 * 1024));
                    System.out.flush();
                    System.out.print(":" + System.in.readAllBytes().length);
                }
                case "reject-input" -> {
                    Files.writeString(Path.of(arguments[1]), Long.toString(ProcessHandle.current().pid()));
                    System.err.print("input rejected");
                    System.err.flush();
                    System.in.close();
                    Thread.sleep(60_000);
                }
                case "sleep" -> {
                    Files.writeString(Path.of(arguments[1]), Long.toString(ProcessHandle.current().pid()));
                    Thread.sleep(60_000);
                }
                case "tree" -> {
                    Files.writeString(Path.of(arguments[1]), Long.toString(ProcessHandle.current().pid()));
                    new ProcessBuilder(command("sleep", arguments[2])).inheritIO().start();
                    Thread.sleep(60_000);
                }
                default -> throw new IllegalArgumentException("Unknown probe");
            }
        }
    }
}
