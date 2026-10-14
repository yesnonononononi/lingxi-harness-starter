package com.summit.sandbox;

import com.summit.core.runtime.workspace.WorkspaceBridge.CommandResult;
import com.summit.core.runtime.RuntimeEnvironment;
import com.summit.core.runtime.workspace.OsType;
import com.summit.core.runtime.workspace.ShellType;
import com.summit.core.runtime.workspace.Workspace;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import com.summit.core.runtime.ProcessRunner;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

class SandboxInitializerTest {
    @TempDir Path temporary;

    @Test
    void checksRunDirectlyWithIntactPathsWithoutAWorkspaceShell() throws Exception {
        Path[] files = resources();
        StubRunner runner = new StubRunner(success(), success(), success());
        initializer(OsType.WINDOWS, null, runner).init(files[0], files[1]);

        assertEquals(List.of(
                List.of(files[1].toString(), "--version"),
                List.of(files[1].toString(), files[0].toString(), "--version"),
                List.of(files[1].toString(), files[0].toString(), "-c", "whoami")), runner.commands);
    }

    @Test
    void policyProbeUsesTheExplicitSettingsFile() throws Exception {
        Path[] files = resources();
        Path settings = Files.writeString(temporary.resolve("settings.json"), "{}");
        StubRunner runner = new StubRunner(success(), success(), success());
        initializer(OsType.WINDOWS, null, runner).init(files[0], files[1], settings);
        assertEquals(List.of(files[1].toString(), files[0].toString(), "--settings", settings.toString(), "-c", "whoami"),
                runner.commands.get(2));
    }

    @Test
    void aFailedSandboxProbeReportsTheFailureWithoutInstalling() throws Exception {
        Path[] files = resources();
        StubRunner runner = new StubRunner(success(), success(),
                new CommandResult(1, "WFP fence is missing", false, false));
        IllegalStateException failure = assertThrows(IllegalStateException.class,
                () -> initializer(OsType.WINDOWS, ShellType.BASH, runner).init(files[0], files[1]));

        assertTrue(failure.getMessage().contains("WFP fence is missing"));
        assertTrue(failure.getMessage().contains("exit code 1"));
        assertTrue(failure.getMessage().contains("installWindows"));
        assertEquals(3, runner.commands.size());
        assertTrue(runner.commands.stream().noneMatch(command -> command.contains("windows-install")));
    }

    @Test
    void aBrokenCliStopsBeforeSandboxOrInstallation() throws Exception {
        Path[] files = resources();
        StubRunner runner = new StubRunner(success(),
                new CommandResult(1, "Cannot find package 'commander'", false, false));
        IllegalStateException failure = assertThrows(IllegalStateException.class,
                () -> initializer(OsType.WINDOWS, ShellType.CMD, runner).init(files[0], files[1]));
        assertTrue(failure.getMessage().contains("SRT CLI loading failed"));
        assertTrue(failure.getMessage().contains("Cannot find package"));
        assertEquals(2, runner.commands.size());
    }

    @Test
    void aBrokenNodeStopsBeforeTheCli() throws Exception {
        Path[] files = resources();
        StubRunner runner = new StubRunner(new CommandResult(-1, "Node did not finish", false, false));
        IllegalStateException failure = assertThrows(IllegalStateException.class,
                () -> initializer(OsType.LINUX, ShellType.BASH, runner).init(files[0], files[1]));
        assertTrue(failure.getMessage().contains("Node check failed"));
        assertEquals(1, runner.commands.size());
    }

    @Test
    void explicitWindowsInstallChecksComponentsThenInstallsAndProbesOnce() throws Exception {
        Path[] files = resources();
        StubRunner runner = new StubRunner(success(), success(), success(), success());
        initializer(OsType.WINDOWS, null, runner).installWindows(files[0], files[1]);
        assertEquals(List.of(files[1].toString(), files[0].toString(), "windows-install"),
                runner.commands.get(2));
        assertEquals(List.of(files[1].toString(), files[0].toString(), "-c", "whoami"),
                runner.commands.get(3));
        assertEquals(4, runner.commands.size());
    }

    @Test
    void cancelledInstallPreservesDiagnosticsAndDoesNotProbe() throws Exception {
        Path[] files = resources();
        StubRunner runner = new StubRunner(success(), success(),
                new CommandResult(2, "Install cancelled at the UAC prompt", false, false));
        IllegalStateException failure = assertThrows(IllegalStateException.class,
                () -> initializer(OsType.WINDOWS, null, runner).installWindows(files[0], files[1]));
        assertTrue(failure.getMessage().contains("UAC prompt"));
        assertEquals(3, runner.commands.size());
    }

    @Test
    void nonWindowsInstallationIsRejectedBeforeStartingAnyCommand() throws Exception {
        Path[] files = resources();
        StubRunner runner = new StubRunner();
        assertThrows(IllegalStateException.class,
                () -> initializer(OsType.LINUX, null, runner).installWindows(files[0], files[1]));
        assertTrue(runner.commands.isEmpty());
    }

    @Test
    void directoriesAndMissingFilesAreRejectedBeforeStartingAnyCommand() throws Exception {
        Path[] files = resources();
        StubRunner runner = new StubRunner();
        SandboxInitializer initializer = initializer(OsType.WINDOWS, null, runner);
        assertThrows(IllegalStateException.class, () -> initializer.init(temporary, files[1]));
        assertThrows(IllegalStateException.class, () -> initializer.init(files[0], null));
        assertTrue(runner.commands.isEmpty());
    }

    @Test
    void invalidTimeoutIsRejectedAtConstruction() {
        assertThrows(IllegalArgumentException.class, () -> new CommandTimeoutConfig(0, TimeUnit.SECONDS));
        assertThrows(IllegalArgumentException.class, () -> new CommandTimeoutConfig(1, null));
    }

    @Test
    void startFailuresKeepTheirIOExceptionCause() throws Exception {
        Path[] files = resources();
        IOException expected = new IOException("Node executable could not be started");
        ProcessRunner runner = new ProcessRunner() {
            @Override public CommandResult run(String[] command, long timeout, TimeUnit unit) throws IOException {
                throw expected;
            }
        };
        assertSame(expected, assertThrows(IOException.class,
                () -> initializer(OsType.WINDOWS, null, runner).init(files[0], files[1])));
    }

    private Path[] resources() throws IOException {
        Path directory = Files.createDirectories(temporary.resolve("bundle with spaces & symbols"));
        return new Path[] { Files.createFile(directory.resolve("cli.js")), Files.createFile(directory.resolve("node.exe")) };
    }

    private SandboxInitializer initializer(OsType os, ShellType shell, ProcessRunner runner) {
        Workspace workspace = new Workspace() {
            public String id() { return "test"; }
            public String workDir() { return temporary.toString(); }
            public Path resolve(String path) { return temporary.resolve(path); }
            public RuntimeEnvironment runtimeEnvironment() {
                return RuntimeEnvironment.builder().osType(os).shellType(shell).build();
            }
        };
        return new SandboxInitializer(new CommandTimeoutConfig(5, TimeUnit.SECONDS), workspace, runner);
    }

    private static CommandResult success() { return new CommandResult(0, "ready", false, false); }

    private static class StubRunner extends ProcessRunner {
        final List<List<String>> commands = new ArrayList<>();
        final ArrayDeque<CommandResult> results;

        StubRunner(CommandResult... results) { this.results = new ArrayDeque<>(Arrays.asList(results)); }

        @Override public CommandResult run(String[] command, long timeout, TimeUnit unit) {
            commands.add(List.of(command.clone()));
            assertEquals(5, timeout);
            assertEquals(TimeUnit.SECONDS, unit);
            return results.removeFirst();
        }
    }
}
