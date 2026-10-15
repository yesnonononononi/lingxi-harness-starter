package com.summit.sandbox.mxc;

import com.summit.core.runtime.workspace.Workspace;
import com.summit.core.runtime.ProcessRunner;
import com.summit.core.runtime.workspace.WorkspaceBridge;
import com.summit.core.workspace.BasicWorkspaceSpec;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.ArrayList;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/** Opt-in real SDK/backend tests, using only a dedicated deployment-owned test directory. */
class MxcWorkspaceIntegrationTest {
    private static MxcRuntime runtime;
    private static String base;
    private MxcWorkspaceProvider provider;
    private String directory;
    private Path root;
    private Path outside;
    private Path readonly;
    private Workspace workspace;

    @BeforeAll static void runtime() {
        String node = System.getProperty("mxc.node");
        String sdk = System.getProperty("mxc.sdk");
        base = System.getProperty("mxc.test.root");
        assumeTrue(node != null && sdk != null && base != null, "Configure mxc.node, mxc.sdk and mxc.test.root for backend tests");
        String distribution = System.getProperty("mxc.wsl.distribution");
        Path helperPath = distribution == null ? Path.of(base, "helpers")
                : Path.of("\\\\wsl.localhost\\" + distribution + (base + "/helpers").replace('/', '\\'));
        runtime = distribution == null ? MxcRuntime.linux(Path.of(node), Path.of(sdk), helperPath)
                : MxcRuntime.wsl(distribution, node, sdk, helperPath);
    }

    @BeforeEach void workspace() throws Exception {
        directory = base + "/case-" + UUID.randomUUID();
        root = runtime.hostPath(directory + "/work space '$& 中文");
        outside = runtime.hostPath(directory + "/outside/secret");
        readonly = runtime.hostPath(directory + "/readonly");
        Files.createDirectories(root);
        Files.createDirectories(outside.getParent());
        Files.createDirectories(readonly);
        Files.writeString(outside, "host-secret");
        Files.writeString(readonly.resolve("visible"), "read-only-data");
        provider = new MxcWorkspaceProvider(runtime);
        workspace = provider.resolve(new MxcWorkspaceSpec(runtime.linuxPath(root.toString()), "test",
                List.of(runtime.linuxPath(readonly.toString())), List.of(), Map.of("VISIBLE_VALUE", "hello '$&中文")));
    }

    @Test void fileOperationsPreserveTextAndReportRealFailures() throws Exception {
        WorkspaceBridge bridge = workspace.bridge();
        Path folder = workspace.resolve("nested '$& 中文");
        Path file = folder.resolve("text");
        assertFalse(bridge.exists(file));
        bridge.createDirectories(folder);
        String text = "hello 中文\r\n'$&\n\nend";
        bridge.writeString(file, text, StandardCharsets.UTF_16LE);
        assertEquals(text, bridge.readString(file, StandardCharsets.UTF_16LE));
        assertEquals(List.of("hello 中文", "'$&", "", "end"), bridge.readLines(file, StandardCharsets.UTF_16LE));
        assertTrue(bridge.exists(file));
        bridge.deleteFile(file);
        bridge.deleteFile(file);
        assertFalse(bridge.exists(file));
        assertThrows(IOException.class, () -> bridge.readString(file, StandardCharsets.UTF_8));
        assertThrows(IOException.class, () -> bridge.deleteFile(folder));
        assertThrows(IOException.class, () -> bridge.writeString(file, "\uD800", StandardCharsets.UTF_8));
        Files.write(file, new byte[] { (byte) 0xC3, 0x28 });
        assertThrows(IOException.class, () -> bridge.readString(file, StandardCharsets.UTF_8));
    }

    @Test void shellAndFileOperationsShareReadonlyAndOutsideBoundaries() throws Exception {
        assertEquals("read-only-data", workspace.bridge().readString(readonly.resolve("visible"), StandardCharsets.UTF_8));
        assertThrows(IOException.class, () -> workspace.bridge().writeString(readonly.resolve("visible"), "bad", StandardCharsets.UTF_8));
        assertThrows(IOException.class, () -> workspace.bridge().readString(outside, StandardCharsets.UTF_8));
        assertThrows(IOException.class, () -> workspace.bridge().writeString(outside, "bad", StandardCharsets.UTF_8));
        WorkspaceBridge.CommandResult result = execute("cat " + q(runtime.linuxPath(outside.toString())) + "; printf bad > "
                + q(runtime.linuxPath(readonly.resolve("visible").toString())), 10, 4096);
        assertNotEquals(0, result.exitCode());
        assertFalse(result.output().contains("host-secret"));
        assertEquals("host-secret", Files.readString(outside));
        assertEquals("read-only-data", Files.readString(readonly.resolve("visible")));
        assertFalse(workspace.encloses(outside));
        assertTrue(workspace.encloses(workspace.resolve("nested/../file")));
    }

    @Test void symlinkAndParentTraversalCannotExposeOutsideFiles() throws Exception {
        assertEquals(0, execute("ln -s " + q(runtime.linuxPath(outside.getParent().toString())) + " link", 10, 4096).exitCode());
        assertThrows(IOException.class, () -> workspace.bridge().readString(workspace.resolve("link/secret"), StandardCharsets.UTF_8));
        assertThrows(IOException.class, () -> workspace.bridge().writeString(workspace.resolve("link/secret"), "bad", StandardCharsets.UTF_8));
        assertThrows(IOException.class, () -> workspace.bridge().readString(workspace.resolve("../outside/secret"), StandardCharsets.UTF_8));
        assertEquals("host-secret", Files.readString(outside));
    }

    @Test void nestedReadonlyMountAndOversizedFilesKeepTheirBoundaries() throws Exception {
        Path nested = Files.createDirectory(root.resolve("readonly-child"));
        Files.writeString(nested.resolve("file"), "nested-data");
        Workspace bound = provider.resolve(new MxcWorkspaceSpec(workspace.workDir(), "test",
                List.of(runtime.linuxPath(nested.toString())), List.of(), Map.of()));
        assertEquals("nested-data", bound.bridge().readString(nested.resolve("file"), StandardCharsets.UTF_8));
        assertThrows(IOException.class, () -> bound.bridge().deleteFile(nested.resolve("file")));
        assertThrows(IOException.class, () -> bound.bridge().writeString(nested.resolve("new"), "bad", StandardCharsets.UTF_8));
        assertTrue(bound.bridge().execute("printf bad > readonly-child/file", bound.workDir(), StandardCharsets.UTF_8,
                10, TimeUnit.SECONDS, 4096).exitCode() != 0);
        Path large = root.resolve("large");
        try (java.io.RandomAccessFile file = new java.io.RandomAccessFile(large.toFile(), "rw")) { file.setLength(16L * 1024 * 1024 + 1); }
        assertThrows(IOException.class, () -> workspace.bridge().readString(large, StandardCharsets.UTF_8));
        assertEquals("nested-data", Files.readString(nested.resolve("file")));
    }

    @Test void readonlyWorkspaceRejectsFileAndTerminalMutations() throws Exception {
        Files.writeString(root.resolve("readonly-input"), "original");
        Workspace bound = provider.resolve(new MxcWorkspaceSpec(workspace.workDir(), "test", List.of(), List.of(), Map.of(), false));
        assertEquals("original", bound.bridge().readString(bound.resolve("readonly-input"), StandardCharsets.UTF_8));
        assertThrows(IOException.class, () -> bound.bridge().writeString(bound.resolve("readonly-input"), "bad", StandardCharsets.UTF_8));
        assertThrows(IOException.class, () -> bound.bridge().createDirectories(bound.resolve("new")));
        assertThrows(IOException.class, () -> bound.bridge().deleteFile(bound.resolve("readonly-input")));
        assertNotEquals(0, bound.bridge().execute("printf bad > readonly-input", bound.workDir(), StandardCharsets.UTF_8,
                10, TimeUnit.SECONDS, 4096).exitCode());
        assertEquals("original", Files.readString(root.resolve("readonly-input")));
    }

    @Test void fileHelperDoesNotLoadCommandEnvironmentPreloads() throws Exception {
        Files.writeString(root.resolve("preload.cjs"), "process.stdout.write('forged'); process.exit(0);");
        Workspace preloaded = provider.resolve(new MxcWorkspaceSpec(workspace.workDir(), "test", List.of(), List.of(),
                Map.of("NODE_OPTIONS", "--require \"" + workspace.workDir() + "/preload.cjs\"")));
        Path text = root.resolve("plain");
        preloaded.bridge().writeString(text, "real", StandardCharsets.UTF_8);
        assertEquals("real", preloaded.bridge().readString(text, StandardCharsets.UTF_8));
    }

    @Test void shellPreservesScriptsAndOnlyExplicitEnvironmentValues() throws Exception {
        WorkspaceBridge.CommandResult result = execute("printf '%s\\n' \"$VISIBLE_VALUE\"\nprintf '%s' 'a | b' | cat\nprintf '\\n'; pwd", 10, 4096);
        assertEquals(0, result.exitCode(), result.output());
        assertTrue(result.output().contains("hello '$&中文\na | b\n"), result.output());
        assertTrue(result.output().contains(workspace.workDir()), result.output());
        assertEquals("unset", execute("printf '%s' \"${USERPROFILE-unset}\"", 10, 4096).output());
        String inheritedKey = List.of("COMPUTERNAME", "HOSTNAME", "USER", "LOGNAME").stream()
                .filter(key -> System.getenv(key) != null).findFirst().orElseThrow();
        assertEquals("unset", execute("printf '%s' \"${" + inheritedKey + "-unset}\"", 10, 4096).output());
        assertEquals(23, execute("exit 23", 10, 4096).exitCode());
        WorkspaceBridge.CommandResult bounded = execute("head -c 100000 /dev/zero | tr '\\0' x", 10, 101);
        assertEquals(101, bounded.output().length());
        assertTrue(bounded.truncated());
        assertEquals(0, bounded.exitCode());
    }

    @Test void executionTimeoutTerminatesDescendants() throws Exception {
        WorkspaceBridge.CommandResult result = execute("(sleep 4; printf leaked > timeout-leak) & wait", 1, 4096);
        assertTrue(result.timedOut(), result.output());
        Thread.sleep(4500);
        assertFalse(Files.exists(root.resolve("timeout-leak")));
    }

    @Test void interruptCancelsTheLinuxProcessTreeAcrossTransport() throws Exception {
        AtomicReference<Throwable> failure = new AtomicReference<>();
        Thread worker = Thread.ofPlatform().start(() -> {
            try { execute("printf ready > started; (sleep 6; printf leaked > interrupt-leak) & wait", 60, 4096); }
            catch (Throwable e) { failure.set(e); }
        });
        long deadline = System.nanoTime() + Duration.ofSeconds(30).toNanos();
        while (!Files.exists(root.resolve("started")) && System.nanoTime() < deadline && worker.isAlive()) Thread.sleep(50);
        assertTrue(Files.exists(root.resolve("started")), "Command must start before testing cancellation");
        worker.interrupt();
        worker.join(Duration.ofSeconds(15));
        assertFalse(worker.isAlive(), "Cancellation must settle");
        assertInstanceOf(InterruptedException.class, failure.get());
        Thread.sleep(6500);
        assertFalse(Files.exists(root.resolve("interrupt-leak")));
    }

    @Test void unavailableSdkAndUnsafeRuntimeGrantsFailClosed() {
        assertThrows(IllegalArgumentException.class, () -> provider.resolve(new BasicWorkspaceSpec("mxc", directory)));
        assertThrows(IllegalStateException.class, () -> provider.resolve(new MxcWorkspaceSpec(directory,
                "test", List.of(), List.of("/"), Map.of())));
        assertThrows(IllegalStateException.class, () -> provider.resolve(new MxcWorkspaceSpec(directory,
                "test", List.of("/"), List.of(), Map.of())));
        MxcRuntime missing = new MxcRuntime(runtime.distribution(), runtime.nodeExecutable(), directory + "/missing-sdk",
                runtime.helperDirectory(), runtime.startupTimeout(), runtime.fileTimeout());
        assertThrows(IllegalStateException.class, () -> new MxcWorkspaceProvider(missing).resolve(new MxcWorkspaceSpec(directory)));
        assertThrows(IllegalStateException.class, () -> provider.resolve(new MxcWorkspaceSpec(directory, "test",
                List.of(), List.of(runtime.linuxPath(runtime.helperDirectory().toString())), Map.of())));
    }

    @Test void hostLoopbackConnectionIsBlockedWithAnUnconfinedPositiveControl() throws Exception {
        String port = System.getProperty("mxc.test.host.port");
        assumeTrue(port != null, "Run with an independently verified Linux host TCP listener");
        String node = runtime.nodeExecutable();
        String script = "const n=require('net');let s=n.connect(" + Integer.parseInt(port)
                + ",'127.0.0.1');s.on('connect',()=>{s.destroy();process.exit(81)});s.on('error',()=>process.exit(0));setTimeout(()=>process.exit(0),1000);";
        assertEquals(81, hostNode(script).exitCode(), "Positive control must connect before sandbox attempt");
        assertEquals(0, execute(q(node) + " -e " + q(script), 5, 4096).exitCode());
        assertEquals(81, hostNode(script).exitCode(), "Listener must still accept connections after sandbox attempt");
    }

    private WorkspaceBridge.CommandResult execute(String script, long timeout, long maxOutput) throws Exception {
        return workspace.bridge().execute(script, workspace.workDir(), StandardCharsets.UTF_8, timeout, TimeUnit.SECONDS, maxOutput);
    }
    private static String q(String value) { return "'" + value.replace("'", "'\\''") + "'"; }
    private static WorkspaceBridge.CommandResult hostNode(String script) throws Exception {
        List<String> argv = new ArrayList<>();
        if (runtime.distribution() != null) argv.addAll(List.of("wsl.exe", "--distribution", runtime.distribution(), "--exec"));
        argv.addAll(List.of(runtime.nodeExecutable(), "-e", script));
        return ProcessRunner.run(argv, null, StandardCharsets.UTF_8, 5, TimeUnit.SECONDS, 4096);
    }
}
