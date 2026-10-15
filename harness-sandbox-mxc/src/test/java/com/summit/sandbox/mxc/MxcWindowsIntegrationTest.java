package com.summit.sandbox.mxc;

import com.summit.core.runtime.workspace.OsType;
import com.summit.core.runtime.ProcessRunner;
import com.summit.core.runtime.workspace.ShellType;
import com.summit.core.runtime.workspace.Workspace;
import com.summit.core.runtime.workspace.WorkspaceBridge;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import java.io.IOException;
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
        WorkspaceBridge.CommandResult result = execute("echo 中文 literal&exit /b 17", 10, 4096);
        assertEquals(17, result.exitCode(), result.output());
        assertTrue(result.output().contains("中文 literal"), result.output());
        assertEquals(19, execute("cmd /d /c exit /b 19", 10, 4096).exitCode());
    }
    @Test void fileOperationsAndOutsideBoundariesShareTheNativePolicy() throws Exception {
        Path folder = workspace.resolve("nested 中文");
        Path file = folder.resolve("text");
        workspace.bridge().createDirectories(folder);
        workspace.bridge().writeString(file, "中文\0\r\nend", StandardCharsets.UTF_16LE);
        assertEquals("中文\0\r\nend", workspace.bridge().readString(file, StandardCharsets.UTF_16LE));
        assertTrue(workspace.bridge().exists(file));
        workspace.bridge().deleteFile(file);
        assertFalse(workspace.bridge().exists(file));
        assertThrows(IOException.class, () -> workspace.bridge().readString(outside, StandardCharsets.UTF_8));
        assertThrows(IOException.class, () -> workspace.bridge().writeString(outside, "changed", StandardCharsets.UTF_8));
        assertNotEquals(0, execute("type \"" + outside + "\"", 10, 4096).exitCode());
        assertEquals("host-secret", Files.readString(outside));
    }
    @Test void readonlyModeRejectsTerminalAndFileMutations() throws Exception {
        Files.writeString(root.resolve("input"), "original");
        Workspace readonly = provider.resolve(new MxcWorkspaceSpec(root.toString(), "test", List.of(), List.of(), Map.of(), false));
        assertEquals("original", readonly.bridge().readString(readonly.resolve("input"), StandardCharsets.UTF_8));
        assertThrows(IOException.class, () -> readonly.bridge().writeString(readonly.resolve("input"), "bad", StandardCharsets.UTF_8));
        assertNotEquals(0, readonly.bridge().execute("echo bad > input", readonly.workDir(), readonly.runtimeEnvironment().charset(), 10, TimeUnit.SECONDS, 4096).exitCode());
        assertEquals("original", Files.readString(root.resolve("input")));
    }
    @Test void junctionAndParentTraversalCannotExposeOutsideFiles() throws Exception {
        assertEquals(0, ProcessRunner.run(List.of(runtime.nodeExecutable(), "-e",
                "require('fs').symlinkSync(process.argv[1],process.argv[2],'junction')",
                outside.getParent().toString(), root.resolve("escape").toString()), null,
                StandardCharsets.UTF_8, 5, TimeUnit.SECONDS, 4096).exitCode());
        assertThrows(IOException.class, () -> workspace.bridge().readString(root.resolve("escape/outside"), StandardCharsets.UTF_8));
        assertThrows(IOException.class, () -> workspace.bridge().writeString(root.resolve("escape/outside"), "bad", StandardCharsets.UTF_8));
        assertThrows(IOException.class, () -> workspace.bridge().readString(workspace.resolve("../outside"), StandardCharsets.UTF_8));
        assertEquals("host-secret", Files.readString(outside));
    }
    @Test void helperIgnoresCommandPreloadsAndWholeDriveGrantsFailClosed() throws Exception {
        Files.writeString(root.resolve("preload.cjs"), "process.stdout.write('forged'); process.exit(0)");
        Workspace preload = provider.resolve(new MxcWorkspaceSpec(root.toString(), "test", List.of(), List.of(),
                Map.of("NODE_OPTIONS", "--require \"" + root.resolve("preload.cjs") + "\"")));
        preload.bridge().writeString(preload.resolve("plain"), "real", StandardCharsets.UTF_8);
        assertEquals("real", preload.bridge().readString(preload.resolve("plain"), StandardCharsets.UTF_8));
        assertThrows(IllegalStateException.class, () -> provider.resolve(new MxcWorkspaceSpec(root.toString(), "test",
                List.of(root.getRoot().toString()), List.of(), Map.of())));
    }
    @Test void outputLimitsAndTimeoutsRemainEffective() throws Exception {
        WorkspaceBridge.CommandResult output = execute("node -e \"process.stdout.write('x'.repeat(10000))\"", 10, 101);
        assertEquals(101, output.output().length());
        assertTrue(output.truncated());
        Files.writeString(root.resolve("timeout-child.cjs"), "require('fs').writeFileSync('child-started','yes');setTimeout(()=>require('fs').writeFileSync('timeout-leak','bad'),5000)");
        WorkspaceBridge.CommandResult timeout = execute("node -e \"require('child_process').spawn(process.execPath,['--preserve-symlinks-main','timeout-child.cjs'],{stdio:'ignore'});setInterval(()=>{},100)\"", 2, 4096);
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
    private WorkspaceBridge.CommandResult execute(String command, long timeout, long limit) throws Exception {
        return workspace.bridge().execute(command, workspace.workDir(), workspace.runtimeEnvironment().charset(), timeout, TimeUnit.SECONDS, limit);
    }
}
