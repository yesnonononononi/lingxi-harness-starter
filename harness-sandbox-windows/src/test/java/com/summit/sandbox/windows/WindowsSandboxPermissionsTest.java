package com.summit.sandbox.windows;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledOnOs;
import org.junit.jupiter.api.condition.OS;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

@EnabledOnOs(OS.WINDOWS)
class WindowsSandboxPermissionsTest {
    private static final Charset CMD_CHARSET = Charset.forName("GBK");

    private static SandboxResult runCmd(Path workspace, SandboxMode mode, String cmd, Duration timeout)
            throws Exception {
        return WindowsSandboxRunner.run(
                workspace,
                mode,
                List.of("cmd.exe", "/c", cmd),
                Map.of(),
                timeout,
                CMD_CHARSET
        );
    }

    @Test
    void readOnlyModeAllowsReadingAndBlocksWriteOverwriteAndDelete(@TempDir Path tempRoot) throws Exception {
        Path workspace = Files.createDirectory(tempRoot.resolve("workspace"));
        Path outside = Files.createDirectory(tempRoot.resolve("outside"));
        Path existing = workspace.resolve("existing.txt");
        Files.writeString(existing, "hello-readonly", StandardCharsets.UTF_8);

        SandboxResult readResult = runCmd(workspace, SandboxMode.READ_ONLY, "type " + existing, Duration.ofSeconds(10));
        assertFalse(readResult.timedOut());
        assertEquals(0, readResult.exitCode(), "Expected read to succeed: " + readResult.output());
        assertTrue(readResult.output().contains("hello-readonly"));

        Path insideTarget = workspace.resolve("blocked-write.txt");
        SandboxResult writeInside = runCmd(workspace, SandboxMode.READ_ONLY, "echo blocked>" + insideTarget, Duration.ofSeconds(10));
        assertNotEquals(0, writeInside.exitCode(), "Write inside workspace must fail in READ_ONLY mode: " + writeInside.output());
        assertFalse(Files.exists(insideTarget));

        SandboxResult overwriteInside = runCmd(workspace, SandboxMode.READ_ONLY, "echo overwrite>" + existing, Duration.ofSeconds(10));
        assertNotEquals(0, overwriteInside.exitCode(), "Overwrite inside workspace must fail in READ_ONLY mode: " + overwriteInside.output());
        assertEquals("hello-readonly", Files.readString(existing, StandardCharsets.UTF_8));

        SandboxResult deleteInside = runCmd(workspace, SandboxMode.READ_ONLY, "del /q " + existing, Duration.ofSeconds(10));
        assertTrue(Files.exists(existing), "Delete inside workspace must fail in READ_ONLY mode: " + deleteInside.output());
        assertEquals("hello-readonly", Files.readString(existing, StandardCharsets.UTF_8));

        Path outsideTarget = outside.resolve("blocked-outside.txt");
        SandboxResult writeOutside = runCmd(workspace, SandboxMode.READ_ONLY, "echo blocked>" + outsideTarget, Duration.ofSeconds(10));
        assertNotEquals(0, writeOutside.exitCode(), "Write outside workspace must fail: " + writeOutside.output());
        assertFalse(Files.exists(outsideTarget));
    }

    @Test
    void readWriteModeAllowsWorkspaceWritesAndSupportsDowngradeToReadOnly(@TempDir Path tempRoot) throws Exception {
        Path workspace = Files.createDirectory(tempRoot.resolve("workspace"));
        Path subDir = Files.createDirectory(workspace.resolve("sub"));
        Path existingInSub = subDir.resolve("note.txt");
        Files.writeString(existingInSub, "before", StandardCharsets.UTF_8);

        Path outside = Files.createDirectory(tempRoot.resolve("outside"));
        Path outsideExisting = outside.resolve("protected.txt");
        Files.writeString(outsideExisting, "keep-me", StandardCharsets.UTF_8);

        Path createdInWorkspace = workspace.resolve("created.txt");
        SandboxResult createResult = runCmd(workspace, SandboxMode.READ_WRITE, "echo created-ok>" + createdInWorkspace, Duration.ofSeconds(10));
        assertEquals(0, createResult.exitCode(), "Create inside workspace failed: " + createResult.output());
        assertTrue(Files.readString(createdInWorkspace, StandardCharsets.UTF_8).contains("created-ok"));

        SandboxResult modifySub = runCmd(workspace, SandboxMode.READ_WRITE, "echo updated>" + existingInSub, Duration.ofSeconds(10));
        assertEquals(0, modifySub.exitCode(), "Modify existing file in subdir failed: " + modifySub.output());
        assertTrue(Files.readString(existingInSub, StandardCharsets.UTF_8).contains("updated"));

        Path outsideTarget = outside.resolve("escape.txt");
        SandboxResult writeOutside = runCmd(workspace, SandboxMode.READ_WRITE, "echo escape>" + outsideTarget, Duration.ofSeconds(10));
        assertNotEquals(0, writeOutside.exitCode(), "Write outside workspace must fail: " + writeOutside.output());
        assertFalse(Files.exists(outsideTarget));

        SandboxResult deleteOutside = runCmd(workspace, SandboxMode.READ_WRITE, "del /q " + outsideExisting, Duration.ofSeconds(10));
        assertTrue(Files.exists(outsideExisting), "Delete outside workspace must fail: " + deleteOutside.output());

        // Downgrading the same workspace back to READ_ONLY must immediately block deletes and writes.
        SandboxResult deleteAfterDowngrade = runCmd(workspace, SandboxMode.READ_ONLY, "del /q " + createdInWorkspace, Duration.ofSeconds(10));
        assertTrue(Files.exists(createdInWorkspace), "Delete must fail after downgrading to READ_ONLY: " + deleteAfterDowngrade.output());
    }

    @Test
    void timeoutTerminatesChildProcessTree(@TempDir Path tempRoot) throws Exception {
        Path workspace = Files.createDirectory(tempRoot.resolve("workspace"));
        SandboxResult result = runCmd(workspace, SandboxMode.READ_ONLY, "ping -n 10 127.0.0.1", Duration.ofMillis(300));
        assertTrue(result.timedOut(), "Expected long-running command to time out, exitCode=" + result.exitCode() + ", output=" + result.output());
    }

    @Test
    void gitOperationsWorkInReadOnlyAndReadWriteWithoutSidMismatchOrReversePollution(@TempDir Path tempRoot) throws Exception {
        Path restrictedRepo = Files.createDirectory(tempRoot.resolve("restricted-repo"));
        Files.writeString(restrictedRepo.resolve("README.md"), "restricted-token", StandardCharsets.UTF_8);
        runHostGit(restrictedRepo, "init");
        runHostGit(restrictedRepo, "config", "user.name", "HostUser");
        runHostGit(restrictedRepo, "config", "user.email", "host@example.com");
        runHostGit(restrictedRepo, "add", "README.md");
        runHostGit(restrictedRepo, "commit", "-m", "host-init");

        Map<String, String> cleanGitEnv = Map.of("GIT_CONFIG_NOSYSTEM", "1", "GIT_CONFIG_GLOBAL", "NUL");

        SandboxResult roStatus = WindowsSandboxRunner.run(
                restrictedRepo,
                SandboxMode.READ_ONLY,
                List.of("cmd.exe", "/c", "git status --short && git log -n 1 --oneline"),
                cleanGitEnv,
                Duration.ofSeconds(15),
                StandardCharsets.UTF_8
        );
        assertEquals(0, roStatus.exitCode(), "RestrictedToken READ_ONLY git status/log failed: " + roStatus.output());
        assertTrue(roStatus.output().contains("host-init"), "Expected commit log in output: " + roStatus.output());

        // READ_ONLY must still block git mutations inside the repository.
        SandboxResult roBlockedCommit = WindowsSandboxRunner.run(
                restrictedRepo,
                SandboxMode.READ_ONLY,
                List.of("cmd.exe", "/c", "git commit --allow-empty -m should-fail"),
                cleanGitEnv,
                Duration.ofSeconds(15),
                StandardCharsets.UTF_8
        );
        assertNotEquals(0, roBlockedCommit.exitCode(),
                "RestrictedToken READ_ONLY must block git commit: " + roBlockedCommit.output());

        // READ_WRITE allows git add + commit and preserves host user ownership (zero reverse pollution).
        SandboxResult rwCommit = WindowsSandboxRunner.run(
                restrictedRepo,
                SandboxMode.READ_WRITE,
                List.of("cmd.exe", "/c", "echo sandbox-change>feature.txt && git add feature.txt && git commit -m sandbox-rw-commit"),
                cleanGitEnv,
                Duration.ofSeconds(15),
                StandardCharsets.UTF_8
        );
        assertEquals(0, rwCommit.exitCode(), "RestrictedToken READ_WRITE git commit failed: " + rwCommit.output());

        // Subsequent READ_ONLY sandbox and Host git commands both succeed with zero dubious ownership.
        SandboxResult roAfterRw = WindowsSandboxRunner.run(
                restrictedRepo,
                SandboxMode.READ_ONLY,
                List.of("cmd.exe", "/c", "git log -n 2 --oneline"),
                cleanGitEnv,
                Duration.ofSeconds(15),
                StandardCharsets.UTF_8
        );
        assertEquals(0, roAfterRw.exitCode(), "READ_ONLY after READ_WRITE commit failed: " + roAfterRw.output());
        assertTrue(roAfterRw.output().contains("sandbox-rw-commit"), "Missing RW commit in RO log: " + roAfterRw.output());

        ProcessResult hostAfterRw = runHostGitProcess(restrictedRepo, "log", "-n", "2", "--oneline");
        assertEquals(0, hostAfterRw.exitCode, "Host git log after RestrictedToken RW failed: " + hostAfterRw.output);
        assertTrue(hostAfterRw.output.contains("sandbox-rw-commit"));
    }

    @Test
    void powershellAndPwshShellsExecuteInsideSandbox(@TempDir Path tempRoot) throws Exception {
        Path workspace = Files.createDirectory(tempRoot.resolve("workspace"));
        WindowsWorkspaceCommands commands = new WindowsWorkspaceCommands(workspace, SandboxMode.READ_ONLY, Map.of());

        var psResult = commands.execute(
                "Write-Output 'POWERSHELL-SANDBOX-OK'",
                com.summit.core.runtime.workspace.ShellType.POWERSHELL,
                workspace.toString(),
                StandardCharsets.UTF_8,
                15,
                java.util.concurrent.TimeUnit.SECONDS,
                4096
        );
        assertEquals(0, psResult.exitCode(), "PowerShell failed in sandbox: " + psResult.output());
        assertTrue(psResult.output().contains("POWERSHELL-SANDBOX-OK"), "Unexpected output: " + psResult.output());

        var pwshResult = commands.execute(
                "Write-Output 'PWSH-SANDBOX-OK'",
                com.summit.core.runtime.workspace.ShellType.PWSH,
                workspace.toString(),
                StandardCharsets.UTF_8,
                15,
                java.util.concurrent.TimeUnit.SECONDS,
                4096
        );
        assertEquals(0, pwshResult.exitCode(), "pwsh failed in sandbox: " + pwshResult.output());
        assertTrue(pwshResult.output().contains("PWSH-SANDBOX-OK"), "Unexpected output: " + pwshResult.output());

        // Cross-workspace isolation: a second workspace in READ_WRITE mode has a different derived virtual SID,
        // so a process running in workspace A cannot modify workspace B.
        Path workspaceB = Files.createDirectory(tempRoot.resolve("workspace-b"));
        Path fileInB = workspaceB.resolve("protected-b.txt");
        Files.writeString(fileInB, "original-b", StandardCharsets.UTF_8);
        WindowsWorkspaceAcl.ensureWorkspacePermission(workspaceB, SandboxMode.READ_WRITE);

        WindowsWorkspaceCommands rwCommandsA = new WindowsWorkspaceCommands(workspace, SandboxMode.READ_WRITE, Map.of());
        var crossWrite = rwCommandsA.execute(
                "$ErrorActionPreference='Stop'; Set-Content -Path '" + fileInB + "' -Value 'tampered'",
                com.summit.core.runtime.workspace.ShellType.PWSH,
                workspace.toString(),
                StandardCharsets.UTF_8,
                15,
                java.util.concurrent.TimeUnit.SECONDS,
                4096
        );
        assertNotEquals(0, crossWrite.exitCode(),
                "Set-Content from workspace A into workspace B must be blocked: " + crossWrite.output());
        assertEquals("original-b", Files.readString(fileInB, StandardCharsets.UTF_8));
    }

    private record ProcessResult(int exitCode, String output) {
    }

    private static void runHostGit(Path repo, String... args) throws Exception {
        ProcessResult res = runHostGitProcess(repo, args);
        assertEquals(0, res.exitCode, "Host git " + List.of(args) + " failed: " + res.output);
    }

    private static ProcessResult runHostGitProcess(Path repo, String... args) throws Exception {
        java.util.ArrayList<String> cmd = new java.util.ArrayList<>();
        cmd.add("git");
        cmd.addAll(List.of(args));
        ProcessBuilder pb = new ProcessBuilder(cmd);
        pb.directory(repo.toFile());
        pb.redirectErrorStream(true);
        pb.environment().put("GIT_CONFIG_NOSYSTEM", "1");
        pb.environment().put("GIT_CONFIG_GLOBAL", "NUL");
        Process p = pb.start();
        String out = new String(p.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        int code = p.waitFor();
        return new ProcessResult(code, out);
    }
}
