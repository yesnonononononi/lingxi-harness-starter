package com.summit.core.runtime;

import com.summit.core.runtime.workspace.LocalWorkspaceBridge;
import com.summit.core.runtime.workspace.ShellType;
import com.summit.core.runtime.workspace.WorkspaceCommands.CommandResult;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.FileDescriptor;
import java.io.FileOutputStream;
import java.io.IOException;
import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Base64;
import java.util.List;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.*;

class ProcessRunnerTest {
    @TempDir Path temporary;

    @Test
    void backgroundFailureKeepsItsResultAfterExitAndAcrossRepeatedWaits() throws Exception {
        CommandHandler handle = ProcessRunner.start(List.of(command("fail")), null, StandardCharsets.UTF_8,
                new byte[0], 5, TimeUnit.SECONDS, 4096);
        CommandResult result = handle.await();
        assertEquals(7, result.exitCode());
        assertEquals("failure diagnostic", handle.outputSnapshot());
        assertSame(result, handle.await());
        assertTrue(handle.isDone());
        assertFalse(handle.stop().stopped());
    }

    @Test
    void backgroundTimeoutSettlesWithoutAWaitingCaller() throws Exception {
        Path pid = temporary.resolve("autonomous-timeout.pid");
        CommandHandler handle = ProcessRunner.start(List.of(command("sleep", pid.toString())), null,
                StandardCharsets.UTF_8, new byte[0], 2, TimeUnit.SECONDS, 64);
        try {
            awaitFile(pid);
            // Completion includes bounded tree termination and I/O cleanup after the command deadline.
            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(12);
            while (!handle.isDone() && System.nanoTime() < deadline) Thread.sleep(10);
            assertTrue(handle.isDone());
            assertTrue(handle.await().timedOut());
            assertProcessGone(pid);
        } finally { handle.stop(); }
    }

    @Test
    void backgroundSnapshotsArriveBeforeCompletionAndStopConfirmsTheProcessTree() throws Exception {
        Path parent = temporary.resolve("background-parent.pid");
        Path child = temporary.resolve("background-child.pid");
        CommandHandler handle = ProcessRunner.start(List.of(command("tree", parent.toString(), child.toString())), null,
                StandardCharsets.UTF_8, new byte[0], 10, TimeUnit.SECONDS, 64);
        try {
            awaitFile(parent);
            awaitFile(child);
            CommandHandler.StopResult stopped = handle.stop();
            assertTrue(stopped.stopped());
            assertSame(stopped.result(), handle.await());
            assertProcessGone(parent);
            assertProcessGone(child);
        } finally { handle.stop(); }
    }

    @Test
    void failedStartPreservesIOExceptionAndDoesNotPoisonTheRunner() throws Exception {
        CancellableProcessRunner runner = new CancellableProcessRunner();
        assertThrows(IOException.class, () -> runner.run(new String[]{temporary.resolve("missing-executable").toString()}, 2, TimeUnit.SECONDS));
        assertEquals("reused", runner.run(command("echo", "reused")).output());
        assertThrows(NullPointerException.class, () -> runner.start(List.of(command("echo", "invalid")), null,
                null, new byte[0], 2, TimeUnit.SECONDS, 10));
    }

    @Test
    void facadeAndCallerOwnedRunnerExposeSeparateLifecycleContracts() {
        assertTrue(Modifier.isFinal(ProcessRunner.class.getModifiers()));
        for (Constructor<?> constructor : ProcessRunner.class.getDeclaredConstructors()) {
            assertTrue(Modifier.isPrivate(constructor.getModifiers()));
        }
        for (Field field : ProcessRunner.class.getDeclaredFields()) {
            assertTrue(Modifier.isStatic(field.getModifiers()), field.getName());
        }
        for (Method method : ProcessRunner.class.getDeclaredMethods()) {
            if (Modifier.isPublic(method.getModifiers())) {
                assertTrue(Modifier.isStatic(method.getModifiers()), method.getName());
            }
        }
        for (Method method : CancellableProcessRunner.class.getDeclaredMethods()) {
            if (Modifier.isPublic(method.getModifiers())) {
                assertFalse(Modifier.isStatic(method.getModifiers()), method.getName());
            }
        }
    }

    @Test
    void cancellingOneRunnerDoesNotTerminateAnotherRunnersCommand() throws Exception {
        CancellableProcessRunner first = new CancellableProcessRunner();
        CancellableProcessRunner second = new CancellableProcessRunner();
        Path firstPid = temporary.resolve("first-runner.pid");
        Path secondPid = temporary.resolve("second-runner.pid");
        try (ExecutorService executor = Executors.newFixedThreadPool(2)) {
            Future<CommandResult> firstResult = executor.submit(() -> first.run(command("sleep", firstPid.toString())));
            Future<CommandResult> secondResult = executor.submit(() -> second.run(command("sleep", secondPid.toString())));
            try {
                awaitFile(firstPid);
                awaitFile(secondPid);
                assertTrue(first.cancel());
                assertFalse(firstResult.get(5, TimeUnit.SECONDS).isSuccess());
                assertProcessGone(firstPid);
                long secondProcessId = Long.parseLong(Files.readString(secondPid));
                assertTrue(ProcessHandle.of(secondProcessId).map(ProcessHandle::isAlive).orElse(false));
                assertFalse(secondResult.isDone());
                assertTrue(second.cancel());
                assertFalse(secondResult.get(5, TimeUnit.SECONDS).isSuccess());
                assertProcessGone(secondPid);
            } finally {
                first.cancel();
                second.cancel();
            }
        }
    }

    @Test
    void concurrentFacadeCallsOwnIndependentRunners() throws Exception {
        Path firstPid = temporary.resolve("first-facade.pid");
        Path secondPid = temporary.resolve("second-facade.pid");
        try (ExecutorService executor = Executors.newFixedThreadPool(2)) {
            Future<CommandResult> first = executor.submit(() -> ProcessRunner.run(List.of(command("sleep", firstPid.toString())),
                    null, StandardCharsets.UTF_8, 10, 4096));
            Future<CommandResult> second = executor.submit(() -> ProcessRunner.run(List.of(command("sleep", secondPid.toString())),
                    null, StandardCharsets.UTF_8, 10, 4096));
            try {
                awaitFile(firstPid);
                awaitFile(secondPid);
                ProcessHandle firstProcess = ProcessHandle.of(Long.parseLong(Files.readString(firstPid))).orElseThrow();
                ProcessHandle secondProcess = ProcessHandle.of(Long.parseLong(Files.readString(secondPid))).orElseThrow();
                assertTrue(first.cancel(true));
                firstProcess.onExit().get(5, TimeUnit.SECONDS);
                assertTrue(secondProcess.isAlive());
                assertFalse(second.isDone());
                assertTrue(second.cancel(true));
                secondProcess.onExit().get(5, TimeUnit.SECONDS);
            } finally {
                first.cancel(true);
                second.cancel(true);
            }
        }
    }

    @Test
    void oneRunnerRejectsOverlappingCommandsBeforeLaunchingAndCanBeReused() throws Exception {
        CancellableProcessRunner runner = new CancellableProcessRunner();
        Path activePid = temporary.resolve("active-runner.pid");
        Path rejectedPid = temporary.resolve("overlap-must-not-start.pid");
        try (ExecutorService executor = Executors.newSingleThreadExecutor()) {
            Future<CommandResult> active = executor.submit(() -> runner.run(command("sleep", activePid.toString())));
            try {
                awaitFile(activePid);
                assertThrows(IllegalStateException.class, () -> runner.run(command("sleep", rejectedPid.toString())));
                assertFalse(Files.exists(rejectedPid));
                assertTrue(runner.cancel());
                assertFalse(active.get(5, TimeUnit.SECONDS).isSuccess());
                assertEquals("reused", runner.run(command("echo", "reused")).output());
            } finally {
                runner.cancel();
            }
        }
    }

    @Test
    void successfulCommandKeepsExitZeroAndLiteralArguments() throws Exception {
        String literal = "spaces & symbols ; $(literal)";
        CommandResult result = new CancellableProcessRunner().run(command("echo", literal), 5, TimeUnit.SECONDS);
        assertEquals(0, result.exitCode());
        assertTrue(result.isSuccess());
        assertEquals(literal, result.output());
    }

    @Test
    void nonzeroExitAndStderrArePreserved() throws Exception {
        CommandResult result = new CancellableProcessRunner().run(command("fail"), 5, TimeUnit.SECONDS);
        assertEquals(7, result.exitCode());
        assertFalse(result.isSuccess());
        assertEquals("failure diagnostic", result.output());
    }

    @Test
    void outputLargerThanAPipeBufferDoesNotDeadlock() throws Exception {
        CommandResult result = new CancellableProcessRunner().run(command("large"), 5, TimeUnit.SECONDS);
        assertTrue(result.isSuccess());
        assertEquals("x".repeat(256 * 1024), result.output());
    }

    @Test
    void stdinBytesAreDeliveredUnchangedAndClosedWithEof() throws Exception {
        byte[] input = "中文\r\nlast line\u0000".getBytes(StandardCharsets.UTF_8);
        CommandResult result = new CancellableProcessRunner().run(command("stdin"), input, 10, TimeUnit.SECONDS);
        assertTrue(result.isSuccess(), result.output());
        assertEquals(Base64.getEncoder().encodeToString(input), result.output());
    }

    @Test
    void omittedStdinStillClosesTheInputPipe() throws Exception {
        CommandResult result = new CancellableProcessRunner().run(command("stdin"), 10, TimeUnit.SECONDS);
        assertTrue(result.isSuccess(), result.output());
        assertEquals("", result.output());
    }

    @Test
    void inputAndOutputLargerThanPipeBuffersCanFlowAtTheSameTime() throws Exception {
        byte[] input = new byte[2 * 1024 * 1024];
        Arrays.fill(input, (byte) 'z');
        CommandResult result = assertTimeoutPreemptively(Duration.ofSeconds(12),
                () -> new CancellableProcessRunner().run(command("duplex"), input, 10, TimeUnit.SECONDS));
        assertTrue(result.isSuccess(), result.output());
        assertEquals("x".repeat(256 * 1024) + ":" + input.length, result.output());
    }

    @Test
    void aChildThatNeverReadsLargeStdinStillTimesOutAndRunnerCanBeReused() throws Exception {
        Path pidFile = temporary.resolve("blocked-input.pid");
        CancellableProcessRunner runner = new CancellableProcessRunner();
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
        CancellableProcessRunner runner = new CancellableProcessRunner();
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
        CommandResult result = new CancellableProcessRunner().run(command("echo", "abcdef"),
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
        CommandResult result = localBridge().commands().execute(shellCommand(command("large")), temporary.toString(),
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
                () -> localBridge().commands().execute(shellCommand(command("sleep", pidFile.toString())),
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
        CancellableProcessRunner runner = new CancellableProcessRunner();
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
                () -> new CancellableProcessRunner().run(command("sleep", pidFile.toString()), 2, TimeUnit.SECONDS));
        assertEquals(-1, result.exitCode());
        assertEquals("", result.output());
        assertProcessGone(pidFile);
    }

    @Test
    void timeoutTerminatesTheSpawnedChildAsWellAsItsParent() throws Exception {
        Path parentFile = temporary.resolve("parent.pid");
        Path childFile = temporary.resolve("child.pid");
        CommandResult result = assertTimeoutPreemptively(Duration.ofSeconds(9),
                () -> new CancellableProcessRunner().run(command("tree", parentFile.toString(), childFile.toString()),
                        3, TimeUnit.SECONDS));
        assertEquals(-1, result.exitCode());
        assertProcessGone(parentFile);
        assertProcessGone(childFile);
    }

    @Test
    void invalidTimeoutDoesNotLaunchTheCommand() throws Exception {
        Path marker = temporary.resolve("should-not-exist");
        CancellableProcessRunner runner = new CancellableProcessRunner();
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
        CancellableProcessRunner runner = new CancellableProcessRunner();
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
        CancellableProcessRunner runner = new CancellableProcessRunner();
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
                    // Bypass the host stdout encoder so the probe preserves the requested raw bytes.
                    try (FileOutputStream rawOut = new FileOutputStream(FileDescriptor.out)) {
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
