package com.summit.sandbox.mxc;

import com.summit.core.runtime.workspace.OsType;
import com.summit.core.runtime.ProcessRunner;
import com.summit.core.runtime.workspace.ShellType;
import com.summit.core.runtime.workspace.Workspace;
import com.summit.core.runtime.workspace.WorkspaceCommands;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import java.io.IOException;
import java.io.File;
import java.util.Arrays;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import static org.junit.jupiter.api.Assertions.*;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/** Native Windows evidence must use the public Java provider, without a WSL transport. */
class MxcWindowsIntegrationTest {
    private static MxcRuntime runtime;
    private MxcWorkspaceProvider provider;
    private Workspace workspace;
    private Path root;
    private Path outside;

    @BeforeAll static void runtime() {
        assumeTrue(System.getProperty("os.name").toLowerCase().contains("windows"));
        String node = System.getProperty("mxc.node");
        String sdk = System.getProperty("mxc.sdk");
        String base = System.getProperty("mxc.test.root");
        assumeTrue(node != null && sdk != null && base != null, "Configure native MXC resources");
        runtime = MxcRuntime.nativeHost(Path.of(node), Path.of(sdk), Path.of(base, "helpers"));
    }
    @BeforeEach void workspace() throws Exception {
        Path directory = Files.createDirectories(runtime.helperDirectory().getParent().resolve("case-" + UUID.randomUUID()));
        root = Files.createDirectories(directory.resolve("work space '$& 中文"));
        outside = Files.writeString(directory.resolve("outside"), "host-secret");
        provider = new MxcWorkspaceProvider(runtime);
        workspace = provider.resolve(new MxcWorkspaceSpec(root.toString()));
    }
    @Test void exposesWindowsCmdAndPreservesExitCodesAndUnicode() throws Exception {
        assertEquals(OsType.WINDOWS, workspace.runtimeEnvironment().osType());
        assertEquals(ShellType.CMD, workspace.runtimeEnvironment().shellType());
        WorkspaceCommands.CommandResult result = execute("echo 中文 literal&exit /b 17", 10, 4096);
        assertEquals(17, result.exitCode(), result.output());
        assertTrue(result.output().contains("中文 literal"), result.output());
        assertEquals(19, execute("cmd /d /c exit /b 19", 10, 4096).exitCode());
    }
    @Test void nodeUtf8ChineseAndEmojiDoNotUseTheOemDecoder() throws Exception {
        for (String text : List.of("命令行中文测试", "emoji test 😀 恭喜")) {
            WorkspaceCommands.CommandResult result = execute("node -e \"console.log('" + text + "')\"", 10, 4096);
            assertEquals(0, result.exitCode(), result.output());
            assertEquals(text + "\n", result.output());
        }
    }
    @Test void cmdAndNodeShareUtf8OnStdoutAndStderr() throws Exception {
        assertEquals(StandardCharsets.UTF_8, workspace.runtimeEnvironment().charset());
        WorkspaceCommands.CommandResult page = execute("chcp", 10, 4096);
        assertEquals(0, page.exitCode(), page.output());
        assertTrue(page.output().contains("65001"), page.output());
        assertEquals("中文😀\r\n", execute("echo 中文😀", 10, 4096).output());
        assertEquals("错误中文😀\r\n", execute("1>&2 echo 错误中文😀", 10, 4096).output());
        assertEquals("错误中文😀\n", execute("node -e \"process.stderr.write('错误中文😀\\n')\"", 10, 4096).output());
        WorkspaceCommands.CommandResult mixed = execute("echo 中文😀&node -e \"console.log('混合输出😀')\"", 10, 4096);
        assertEquals(0, mixed.exitCode(), mixed.output());
        assertEquals("中文😀\r\n混合输出😀\n", mixed.output());
    }
    @Test void utf8StartupDoesNotReparseTheUserScript() throws Exception {
        Files.writeString(root.resolve("quoted 中文.cjs"), "console.log(JSON.stringify(process.argv.slice(2)))");
        WorkspaceCommands.CommandResult quoted = execute("node \"quoted 中文.cjs\" \"中文 😀 & | % !\"", 10, 4096);
        assertEquals(0, quoted.exitCode(), quoted.output());
        assertEquals("[\"中文 😀 & | % !\"]\n", quoted.output());
        assertEquals("管道中文😀\n", execute("echo ignored|node -e \"process.stdin.resume();process.stdin.on('end',()=>console.log('管道中文😀'))\"", 10, 4096).output());
        WorkspaceCommands.CommandResult redirected = execute("echo 重定向中文😀>\"out 中文.txt\"", 10, 4096);
        assertEquals(0, redirected.exitCode(), redirected.output());
        assertEquals("重定向中文😀\r\n", Files.readString(root.resolve("out 中文.txt")));
    }
    @Test void utf8OutputTruncationDoesNotDecodeAnIncompleteByteSequence() throws Exception {
        WorkspaceCommands.CommandResult result = execute("node -e \"process.stdout.write('中'.repeat(10000))\"", 10, 2);
        assertEquals(0, result.exitCode(), result.output());
        assertEquals("中中", result.output());
        assertTrue(result.truncated());
    }
    @Test void fileOperationsAndOutsideBoundariesShareTheNativePolicy() throws Exception {
        Path folder = workspace.resolve("nested 中文");
        Path file = folder.resolve("text");
        workspace.bridge().files().createDirectories(folder);
        workspace.bridge().files().writeString(file, "中文\0\r\nend", StandardCharsets.UTF_16LE);
        assertEquals("中文\0\r\nend", workspace.bridge().files().readString(file, StandardCharsets.UTF_16LE));
        assertTrue(workspace.bridge().files().exists(file));
        workspace.bridge().files().deleteFile(file);
        assertFalse(workspace.bridge().files().exists(file));
        assertThrows(IOException.class, () -> workspace.bridge().files().readString(outside, StandardCharsets.UTF_8));
        assertThrows(IOException.class, () -> workspace.bridge().files().writeString(outside, "changed", StandardCharsets.UTF_8));
        assertNotEquals(0, execute("type \"" + outside + "\"", 10, 4096).exitCode());
        assertEquals("host-secret", Files.readString(outside));
    }
    @Test void readonlyModeRejectsTerminalAndFileMutations() throws Exception {
        Files.writeString(root.resolve("input"), "original");
        Workspace readonly = provider.resolve(new MxcWorkspaceSpec(root.toString(), "test", List.of(), List.of(), Map.of(), false));
        assertEquals("original", readonly.bridge().files().readString(readonly.resolve("input"), StandardCharsets.UTF_8));
        assertThrows(IOException.class, () -> readonly.bridge().files().writeString(readonly.resolve("input"), "bad", StandardCharsets.UTF_8));
        assertNotEquals(0, readonly.bridge().commands().execute("echo bad > input", readonly.workDir(), readonly.runtimeEnvironment().charset(), 10, TimeUnit.SECONDS, 4096).exitCode());
        assertEquals("original", Files.readString(root.resolve("input")));
    }
    @Test void junctionAndParentTraversalCannotExposeOutsideFiles() throws Exception {
        assertEquals(0, ProcessRunner.run(List.of(runtime.nodeExecutable(), "-e",
                "require('fs').symlinkSync(process.argv[1],process.argv[2],'junction')",
                outside.getParent().toString(), root.resolve("escape").toString()), null,
                StandardCharsets.UTF_8, 5, TimeUnit.SECONDS, 4096).exitCode());
        assertThrows(IOException.class, () -> workspace.bridge().files().readString(root.resolve("escape/outside"), StandardCharsets.UTF_8));
        assertThrows(IOException.class, () -> workspace.bridge().files().writeString(root.resolve("escape/outside"), "bad", StandardCharsets.UTF_8));
        assertThrows(IOException.class, () -> workspace.bridge().files().readString(workspace.resolve("../outside"), StandardCharsets.UTF_8));
        assertEquals("host-secret", Files.readString(outside));
    }
    @Test void helperIgnoresCommandPreloadsAndWholeDriveGrantsFailClosed() throws Exception {
        Files.writeString(root.resolve("preload.cjs"), "process.stdout.write('forged'); process.exit(0)");
        Workspace preload = provider.resolve(new MxcWorkspaceSpec(root.toString(), "test", List.of(), List.of(),
                Map.of("NODE_OPTIONS", "--preserve-symlinks --preserve-symlinks-main --require \""
                        + root.resolve("preload.cjs").toString().replace('\\', '/') + "\"")));
        preload.bridge().files().writeString(preload.resolve("plain"), "real", StandardCharsets.UTF_8);
        assertEquals("real", preload.bridge().files().readString(preload.resolve("plain"), StandardCharsets.UTF_8));
        assertEquals("shell中文😀\r\n", preload.bridge().commands().execute("echo shell中文😀", preload.workDir(),
                preload.runtimeEnvironment().charset(), 10, TimeUnit.SECONDS, 4096).output());
        assertEquals("forged", preload.bridge().commands().execute("node -e \"console.log('unreachable')\"", preload.workDir(),
                preload.runtimeEnvironment().charset(), 10, TimeUnit.SECONDS, 4096).output());
        assertThrows(IllegalStateException.class, () -> provider.resolve(new MxcWorkspaceSpec(root.toString(), "test",
                List.of(root.getRoot().toString()), List.of(), Map.of())));
    }
    @Test void outputLimitsAndTimeoutsRemainEffective() throws Exception {
        WorkspaceCommands.CommandResult output = execute("node -e \"process.stdout.write('x'.repeat(10000))\"", 10, 101);
        assertEquals(101, output.output().length());
        assertTrue(output.truncated());
        Files.writeString(root.resolve("timeout-child.cjs"), "require('fs').writeFileSync('child-started','yes');setTimeout(()=>require('fs').writeFileSync('timeout-leak','bad'),5000)");
        WorkspaceCommands.CommandResult timeout = execute("node -e \"require('child_process').spawn(process.execPath,['--preserve-symlinks-main','timeout-child.cjs'],{stdio:'ignore'});setInterval(()=>{},100)\"", 2, 4096);
        assertTrue(timeout.timedOut(), timeout.output());
        assertTrue(Files.exists(root.resolve("child-started")), "Child must start before testing process-tree termination");
        Thread.sleep(5500);
        assertFalse(Files.exists(root.resolve("timeout-leak")));
    }
    @Test void hostLoopbackIsDeniedBetweenPositiveControls() throws Exception {
        try (ServerSocket listener = new ServerSocket(0)) {
            String script = "const s=require('net').connect(" + listener.getLocalPort()
                    + ",'127.0.0.1');s.on('connect',()=>{s.destroy();process.exit(81)});s.on('error',()=>process.exit(0));setTimeout(()=>process.exit(0),1000)";
            assertEquals(81, ProcessRunner.run(List.of(runtime.nodeExecutable(), "-e", script), null,
                    StandardCharsets.UTF_8, 5, TimeUnit.SECONDS, 4096).exitCode());
            assertEquals(0, execute("node -e \"" + script + "\"", 5, 4096).exitCode());
            try (Socket control = new Socket("127.0.0.1", listener.getLocalPort())) { assertTrue(control.isConnected()); }
        }
    }
    @Test void interruptionSettlesAndStopsAStartedWorkload() throws Exception {
        AtomicReference<Throwable> failure = new AtomicReference<>();
        Thread worker = Thread.ofPlatform().start(() -> {
            try { execute("echo yes>started & node -e \"setTimeout(()=>require('fs').writeFileSync('interrupt-leak','bad'),5000)\"", 60, 4096); }
            catch (Throwable error) { failure.set(error); }
        });
        long deadline = System.nanoTime() + Duration.ofSeconds(20).toNanos();
        while (!Files.exists(root.resolve("started")) && worker.isAlive() && System.nanoTime() < deadline) Thread.sleep(50);
        assertTrue(Files.exists(root.resolve("started")), "Workload must start before interruption");
        worker.interrupt(); worker.join(Duration.ofSeconds(15));
        assertFalse(worker.isAlive());
        assertInstanceOf(InterruptedException.class, failure.get());
        Thread.sleep(5500);
        assertFalse(Files.exists(root.resolve("interrupt-leak")));
    }
    @Test void ambientVolumesAllowOtherProjectsButDenyPrivateTreesAndRuntimeWrites() throws Exception {
        Path privateDirectory = Files.createDirectory(outside.getParent().resolve("private"));
        Path secret = Files.writeString(privateDirectory.resolve("secret.txt"), "PRIVATE-CANARY");
        Path otherProject = Files.createDirectory(outside.getParent().resolve("other-project"));
        Path ordinary = Files.writeString(otherProject.resolve("ordinary.txt"), "PUBLIC-CANARY");
        MxcSecurityPolicy policy = new MxcSecurityPolicy(List.of(privateDirectory.toString()),
                Arrays.stream(File.listRoots()).map(File::getAbsolutePath).toList(), true);
        workspace = provider.resolve(new MxcWorkspaceSpec(root.toString(), "privacy", List.of(), List.of(), Map.of(), true, policy));
        assertEquals("PUBLIC-CANARY", workspace.bridge().files().readString(ordinary, StandardCharsets.UTF_8));
        workspace.bridge().files().writeString(otherProject.resolve("created.txt"), "cross-project", StandardCharsets.UTF_8);
        assertEquals("cross-project", Files.readString(otherProject.resolve("created.txt")));
        assertThrows(IOException.class, () -> workspace.bridge().files().readString(secret, StandardCharsets.UTF_8));
        assertThrows(IOException.class, () -> workspace.bridge().files().writeString(secret, "overwrite", StandardCharsets.UTF_8));
        Files.writeString(root.resolve("privacy-child.cjs"), "const fs=require('fs');try{fs.readFileSync(process.argv[2]);process.exit(81)}catch(e){if(!['EPERM','EACCES'].includes(e.code))throw e;console.log('native-denied')}");
        WorkspaceCommands.CommandResult child = execute("node privacy-child.cjs \"" + secret + "\"", 10, 4096);
        assertEquals(0, child.exitCode(), child.output());
        assertTrue(child.output().contains("native-denied"), child.output());
        assertEquals("PRIVATE-CANARY", Files.readString(secret));
        assertEquals(0, execute("dir /b \"" + otherProject + "\"", 10, 4096).exitCode());
        Path forbiddenRuntimeWrite = runtime.helperDirectory().resolve("runtime-overwrite-canary");
        assertThrows(IOException.class, () -> workspace.bridge().files().writeString(forbiddenRuntimeWrite, "bad", StandardCharsets.UTF_8));
        assertFalse(Files.exists(forbiddenRuntimeWrite));
        Files.writeString(root.resolve("runtime-write.cjs"), "try{require('fs').writeFileSync(process.argv[2],'bad');process.exit(81)}catch(e){if(!['EPERM','EACCES'].includes(e.code))throw e;console.log('native-readonly')}");
        WorkspaceCommands.CommandResult runtimeWrite = execute("node runtime-write.cjs \"" + forbiddenRuntimeWrite + "\"", 10, 4096);
        assertEquals(0, runtimeWrite.exitCode(), runtimeWrite.output());
        assertTrue(runtimeWrite.output().contains("native-readonly"));
        assertFalse(Files.exists(forbiddenRuntimeWrite));
        Path alias = root.resolve("private-alias");
        WorkspaceCommands.CommandResult junction = ProcessRunner.run(List.of("cmd.exe", "/d", "/c", "mklink", "/J", alias.toString(), privateDirectory.toString()), null,
                StandardCharsets.UTF_8, 10, TimeUnit.SECONDS, 4096);
        assertEquals(0, junction.exitCode(), junction.output());
        assertThrows(IOException.class, () -> workspace.bridge().files().readString(alias.resolve("secret.txt"), StandardCharsets.UTF_8));
    }
    @Test void configuredNativeDenialsAlsoApplyToFuturePaths() throws Exception {
        Path privateDirectory = outside.getParent().resolve("future-private");
        workspace = provider.resolve(new MxcWorkspaceSpec(root.toString(), "privacy", List.of(),
                List.of(outside.getParent().toString()), Map.of(), true,
                new MxcSecurityPolicy(List.of(privateDirectory.toString()), List.of(), false)));
        Files.createDirectory(privateDirectory);
        Path secret = Files.writeString(privateDirectory.resolve("secret.txt"), "FUTURE-PRIVATE");
        assertThrows(IOException.class, () -> workspace.bridge().files().readString(secret, StandardCharsets.UTF_8));
    }
    @Test void explicitOutboundAllowanceConnectsToAPublicEndpoint() throws Exception {
        String script = "const s=require('net').connect(443,'1.1.1.1');s.on('connect',()=>{s.destroy();process.exit(81)});s.on('error',()=>process.exit(82));setTimeout(()=>process.exit(83),5000)";
        int host = ProcessRunner.run(List.of(runtime.nodeExecutable(), "-e", script), null,
                StandardCharsets.UTF_8, 8, TimeUnit.SECONDS, 4096).exitCode();
        assumeTrue(host == 81, "Host public TCP control unavailable");
        workspace = provider.resolve(new MxcWorkspaceSpec(root.toString(), "network", List.of(), List.of(), Map.of(), true,
                new MxcSecurityPolicy(List.of(), List.of(), true)));
        assertEquals(81, execute("node -e \"" + script + "\"", 8, 4096).exitCode());
    }
    private WorkspaceCommands.CommandResult execute(String command, long timeout, long limit) throws Exception {
        return workspace.bridge().commands().execute(command, workspace.workDir(), workspace.runtimeEnvironment().charset(), timeout, TimeUnit.SECONDS, limit);
    }
}
