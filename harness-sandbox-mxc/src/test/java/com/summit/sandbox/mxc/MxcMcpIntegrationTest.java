package com.summit.sandbox.mxc;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.summit.adapter.langchain4j.mcp.AgentScopeMcpProvider;
import com.summit.adapter.langchain4j.mcp.McpClientFactory;
import com.summit.core.conf.McpConfig;
import com.summit.core.conf.McpTransport;
import com.summit.core.mcp.McpToolScope;
import com.summit.core.runtime.workspace.Workspace;
import com.summit.core.tool.ToolExecution;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import static org.junit.jupiter.api.Assertions.*;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/** Opt-in, real protocol and real host-native isolation on either supported OS. */
@Timeout(90)
class MxcMcpIntegrationTest {
    private MxcRuntime runtime;
    private Workspace workspace;
    private Path root, outside, readonly;
    private final ObjectMapper json = new ObjectMapper();
    private final AgentScopeMcpProvider provider = new AgentScopeMcpProvider(new McpClientFactory());

    @BeforeEach void setup() throws Exception {
        String node = System.getProperty("mxc.node"), sdk = System.getProperty("mxc.sdk"), base = System.getProperty("mxc.test.root");
        assumeTrue(node != null && sdk != null && base != null, "Configure native MXC resources");
        Path directory = Files.createDirectories(Path.of(base, "mcp-" + UUID.randomUUID()));
        root = Files.createDirectory(directory.resolve("work space 中文"));
        outside = Files.writeString(directory.resolve("outside"), "host-secret");
        readonly = Files.createDirectory(directory.resolve("readonly"));
        Files.writeString(readonly.resolve("file"), "read-only-data");
        try (var fixture = getClass().getResourceAsStream("/mxc/mcp-fixture.mjs")) {
            Files.copy(fixture, root.resolve("server.mjs"));
        }
        runtime = MxcRuntime.nativeHost(Path.of(node), Path.of(sdk), Path.of(base, "helpers"));
        workspace = new MxcWorkspaceProvider(runtime).resolve(new MxcWorkspaceSpec(root.toString(), "test",
                List.of(readonly.toString()), List.of(), Map.of()));
    }
    @Test void protocolPreservesUnicodeArgvAndSeparatesBoundedDiagnostics() throws Exception {
        try (McpToolScope scope = provider.openScope(config("hello world 中文", "a\"b", "$&|<>"), workspace)) {
            assertNotNull(scope.getTool("mcp_probe"));
            for (int i = 0; i < 3; i++) {
                var result = json.readTree(call(scope, Map.of("value", "中文 " + i)));
                assertEquals("中文 " + i, result.path("value").asText());
                assertEquals("hello world 中文", result.path("argv").get(0).asText());
                assertEquals("a\"b", result.path("argv").get(1).asText());
                assertEquals("$&|<>", result.path("argv").get(2).asText());
                assertEquals("visible 中文", result.path("env").asText());
            }
            String large = "中文".repeat(40000);
            assertEquals(large, json.readTree(call(scope, Map.of("value", large))).path("value").asText());
        }
        assertStopped(pid("server.pid"));
        assertControlsClean();
    }
    @Test void fileAndNetworkIsolationHavePositiveControls() throws Exception {
        try (ServerSocket listener = new ServerSocket(0); Socket control = new Socket("127.0.0.1", listener.getLocalPort());
             Socket accepted = listener.accept(); McpToolScope scope = provider.openScope(config(), workspace)) {
            assertEquals("read-only-data", call(scope, Map.of("op", "read", "path", readonly.resolve("file").toString())));
            assertEquals("DENIED", call(scope, Map.of("op", "write", "path", readonly.resolve("file").toString())));
            assertEquals("DENIED", call(scope, Map.of("op", "read", "path", outside.toString())));
            String outsideWrite = call(scope, Map.of("op", "write", "path", outside.toString()));
            if (runtime.windows()) assertEquals("DENIED", outsideWrite);
            // Bubblewrap may write a private synthetic ancestor, never the unmapped host canary.
            assertEquals("host-secret", Files.readString(outside));
            assertEquals("WROTE", call(scope, Map.of("op", "write", "path", root.resolve("allowed").toString())));
            assertEquals("bad", Files.readString(root.resolve("allowed")));
            assertEquals("DENIED", call(scope, Map.of("op", "network", "port", listener.getLocalPort())));
        }
        assertEquals("host-secret", Files.readString(outside));
        assertEquals("read-only-data", Files.readString(readonly.resolve("file")));
        assertControlsClean();
    }
    @Test void closeTerminatesConfirmedChildAndIsIdempotent() throws Exception {
        McpToolScope scope = provider.openScope(config(), workspace);
        List<ProcessHandle> processes;
        try {
            call(scope, Map.of("op", "child"));
            if (runtime.windows()) {
                processes = List.of(ProcessHandle.of(pid("server.pid")).orElseThrow(), ProcessHandle.of(pid("child.pid")).orElseThrow());
            } else {
                try (var all = ProcessHandle.allProcesses()) {
                    processes = all.filter(p -> p.info().commandLine().orElse("").contains(root.resolve("server.mjs").toString()))
                            .flatMap(p -> java.util.stream.Stream.concat(java.util.stream.Stream.of(p), p.descendants())).distinct().toList();
                }
            }
            assertFalse(processes.isEmpty(), "fixture must be running before close");
        } finally { scope.close(); scope.close(); }
        for (ProcessHandle process : processes) {
            for (int i = 0; i < 100 && process.isAlive(); i++) Thread.sleep(25);
            assertFalse(process.isAlive(), "Host process survived close: " + process.pid());
        }
        assertStopped(pid("server.pid"));
        assertStopped(pid("child.pid"));
        assertControlsClean();
    }
    @Test void initializationFailureClosesSupervisorAndControlFiles() throws Exception {
        try (var scope = provider.openScope(config("fail-init"), workspace)) { assertTrue(scope.isEmpty()); }
        assertStopped(pid("server.pid"));
        assertControlsClean();
    }
    @Test void preparedSessionCanCloseBeforeTransportStartup() throws Exception {
        var launcher = (com.summit.core.runtime.workspace.WorkspaceStdioLauncher) workspace.bridge();
        var launch = launcher.prepareStdio(List.of(runtime.nodeExecutable(), "unused.mjs"), Map.of());
        launch.close(); launch.close();
        assertControlsClean();
    }
    @Test void interruptedCallerStillClosesTheSessionAndKeepsInterruptFlag() throws Exception {
        var scope = provider.openScope(config(), workspace);
        assertNotNull(scope.getTool("mcp_probe"));
        Thread.currentThread().interrupt();
        try { scope.close(); assertTrue(Thread.currentThread().isInterrupted()); }
        finally { Thread.interrupted(); }
        assertStopped(pid("server.pid"));
        assertControlsClean();
    }

    @Test void windowsBatchEntrypointPreservesSupportedArguments() throws Exception {
        assumeTrue(runtime.windows(), "Windows batch entrypoint");
        Path entry = root.resolve("entry point.cmd");
        Files.writeString(entry, "@echo off\r\n\"" + runtime.nodeExecutable()
                + "\" --preserve-symlinks-main --preserve-symlinks \"%~dp0server.mjs\" %*\r\n");
        McpConfig config = config();
        var original = config.getMcp().getFirst();
        var stdio = (McpConfig.Stdio) original.conf();
        config.setMcp(List.of(new McpConfig.MCP(original.name(), null, McpTransport.STDIO,
                new McpConfig.Stdio(List.of(entry.toString(), "hello world 中文", "a&b!^|<>"), stdio.env(),
                        stdio.initializationTimeout(), stdio.executionTimeout()), original.maxOutput())));
        try (var scope = provider.openScope(config, workspace)) {
            var result = json.readTree(call(scope, Map.of("value", "batch")));
            assertEquals("hello world 中文", result.path("argv").get(0).asText());
            assertEquals("a&b!^|<>", result.path("argv").get(1).asText());
        }
        assertControlsClean();
    }

    private McpConfig config(String... args) {
        var command = new java.util.ArrayList<>(List.of(runtime.nodeExecutable(), "--preserve-symlinks-main", "--preserve-symlinks", root.resolve("server.mjs").toString()));
        command.addAll(List.of(args));
        McpConfig config = new McpConfig();
        config.setMcp(List.of(new McpConfig.MCP("fixture", null, McpTransport.STDIO,
                new McpConfig.Stdio(command, Map.of("VISIBLE", "visible 中文"), Duration.ofSeconds(10), Duration.ofSeconds(10)), 100000)));
        return config;
    }
    private String call(McpToolScope scope, Map<String, Object> args) throws Exception {
        var tool = scope.getTool("mcp_probe");
        assertNotNull(tool, "fixture did not initialize");
        var result = tool.executor().execute(ToolExecution.builder().id(UUID.randomUUID().toString())
                .toolDefinition(tool).workspace(workspace).args(json.writeValueAsString(args)).build());
        assertEquals(1, result.getCode(), result.getToolOutput());
        return result.getToolOutput();
    }
    private long pid(String file) throws Exception { return Long.parseLong(Files.readString(root.resolve(file))); }
    private void assertStopped(long pid) throws Exception {
        // Linux namespace PIDs differ from host PIDs: use the namespace's disappearance via supervisor cleanup.
        if (!runtime.windows()) return;
        for (int i = 0; i < 100 && ProcessHandle.of(pid).map(ProcessHandle::isAlive).orElse(false); i++) Thread.sleep(25);
        assertFalse(ProcessHandle.of(pid).map(ProcessHandle::isAlive).orElse(false), "Process survived close: " + pid);
    }
    private void assertControlsClean() throws Exception {
        try (var files = Files.list(runtime.helperDirectory().resolve("requests"))) { assertEquals(0, files.count()); }
    }
}
