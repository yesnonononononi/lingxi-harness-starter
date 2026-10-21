package com.summit.adapter.langchain4j.mcp;

import com.summit.core.conf.McpConfig;
import com.summit.core.conf.McpTransport;
import com.summit.core.mcp.ScopeMcpProvider;
import com.summit.core.runtime.RuntimeEnvironment;
import com.summit.core.runtime.workspace.*;
import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class McpWorkspaceAdapterTest {
    @Test void rejectsEntireMixedConfigurationBeforeOpeningAnyClient() {
        AtomicInteger created = new AtomicInteger();
        McpClientFactory factory = new McpClientFactory() {
            @Override public dev.langchain4j.mcp.client.McpClient create(String key, McpConfig.MCP server, Workspace workspace) {
                created.incrementAndGet(); throw new AssertionError("must not start");
            }
        };
        McpConfig config = config(stdio(), new McpConfig.MCP("http", null, McpTransport.STREAMABLE_HTTP,
                new McpConfig.StreamableHttp("https://example.test", Map.of(), Duration.ofSeconds(1), Duration.ofSeconds(1)), 100));
        assertThrows(IllegalArgumentException.class, () -> new AgentScopeMcpProvider(factory).openScope(config, workspace(true, null)));
        assertEquals(0, created.get());
    }
    @Test void unsupportedIsolatedBridgeAndLegacyProviderNeverFallBack() {
        assertThrows(IllegalStateException.class, () -> new AgentScopeMcpProvider(new McpClientFactory())
                .openScope(config(stdio()), workspace(true, null)));
        ScopeMcpProvider legacy = config -> { throw new AssertionError("local fallback"); };
        assertThrows(IllegalStateException.class, () -> legacy.openScope(config(stdio()), workspace(true, null)));
        assertThrows(IllegalStateException.class, () -> new McpClientFactory().create("stdio", stdio(), workspace(true, null)));
    }
    @Test void localWorkspacePreservesExistingFactoryOverride() {
        AtomicInteger calls = new AtomicInteger();
        McpClientFactory factory = new McpClientFactory() {
            @Override public dev.langchain4j.mcp.client.McpClient create(String key, McpConfig.MCP server) {
                calls.incrementAndGet(); return null;
            }
        };
        factory.create("stdio", stdio(), workspace(false, null));
        assertEquals(1, calls.get());
    }
    @Test void isolatedPreparationUsesBridgeArgvAndClosesOnce() throws Exception {
        AtomicInteger closed = new AtomicInteger();
        class Bridge extends LocalWorkspaceBridgePlaceholder implements WorkspaceStdioLauncher {
            public WorkspaceStdioLaunch prepareStdio(List<String> command, Map<String, String> env) {
                assertEquals(List.of("not-on-host-path", "hello world"), command);
                assertEquals(Map.of("VISIBLE", "yes"), env);
                return new WorkspaceStdioLaunch(List.of("sandbox-wrapper"), Map.of(), closed::incrementAndGet);
            }
        }
        WorkspaceStdioLaunch launch = McpWorkspaceAdapter.prepare((McpConfig.Stdio) stdio().conf(), workspace(true, new Bridge()));
        OwnedStdioMcpTransport transport = new OwnedStdioMcpTransport(launch);
        transport.close(); transport.close();
        assertEquals(1, closed.get());
        assertThrows(IllegalStateException.class, () -> transport.start(null));
    }
    @Test void isolatedWorkspaceRejectsAnExplicitWorkDir() {
        McpConfig.MCP server = new McpConfig.MCP("stdio", null, McpTransport.STDIO, new McpConfig.Stdio(
                List.of("npx"), Map.of(), "D:/tools", Duration.ofSeconds(1), Duration.ofSeconds(1)), 100);
        assertThrows(IllegalArgumentException.class,
                () -> McpWorkspaceAdapter.validate(config(server), workspace(true, null)));
    }
    private static McpConfig config(McpConfig.MCP... servers) {
        McpConfig config = new McpConfig(); config.setMcp(List.of(servers)); return config;
    }
    private static McpConfig.MCP stdio() {
        return new McpConfig.MCP("stdio", null, McpTransport.STDIO, new McpConfig.Stdio(
                List.of("not-on-host-path", "hello world"), Map.of("VISIBLE", "yes"), Duration.ofSeconds(1), Duration.ofSeconds(1)), 100);
    }
    private static Workspace workspace(boolean isolated, WorkspaceBridge bridge) {
        return new Workspace() {
            public String id() { return "test"; }
            public String workDir() { return Path.of(".").toAbsolutePath().toString(); }
            public Path resolve(String path) { return Path.of(path); }
            public RuntimeEnvironment runtimeEnvironment() { return RuntimeEnvironment.builder().isolated(isolated).build(); }
            public WorkspaceBridge bridge() { return bridge; }
        };
    }
    private abstract static class LocalWorkspaceBridgePlaceholder implements WorkspaceBridge {
        public WorkspaceFiles files() { throw new UnsupportedOperationException(); }
        public WorkspaceCommands commands() { throw new UnsupportedOperationException(); }
    }
}
