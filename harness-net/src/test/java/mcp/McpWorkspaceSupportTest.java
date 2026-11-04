package mcp;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.summit.core.conf.McpConfig;
import com.summit.core.conf.McpTransport;
import com.summit.core.mcp.ScopeMcpProvider;
import com.summit.core.runtime.RuntimeEnvironment;
import com.summit.core.runtime.workspace.Workspace;
import com.summit.core.runtime.workspace.WorkspaceBridge;
import com.summit.core.runtime.workspace.WorkspaceCommands;
import com.summit.core.runtime.workspace.WorkspaceFiles;
import com.summit.core.runtime.workspace.WorkspaceStdioLaunch;
import com.summit.core.runtime.workspace.WorkspaceStdioLauncher;
import mcp.json.Jackson2McpJsonMapper;
import mcp.transport.McpOwnedStdioTransport;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Isolation policy: an isolated workspace either accepts the whole configuration or nothing starts.
 *
 * <p>The counting transport builder is the witness: a policy failure must be raised before any
 * transport is built, so a mixed configuration cannot leave a host-side process behind.</p>
 */
class McpWorkspaceSupportTest {

    private static final Duration INIT = Duration.ofSeconds(1);
    private static final Duration EXEC = Duration.ofSeconds(1);

    @Test
    void rejectsEntireMixedConfigurationBeforeOpeningAnyTransport() {
        AtomicInteger builds = new AtomicInteger();
        McpSessionFactory factory = factoryWith(builds);
        McpConfig config = config(stdio(), new McpConfig.MCP("http", null, McpTransport.STREAMABLE_HTTP,
                new McpConfig.StreamableHttp("https://example.test/mcp", Map.of(), INIT, EXEC), 100));

        assertThrows(IllegalArgumentException.class,
                () -> new AgentScopeMcpProvider(factory).openScope(config, workspace(true, null)));
        assertEquals(0, builds.get(), "no transport may be built for a rejected configuration");
    }

    @Test
    void unsupportedIsolatedBridgeAndLegacyProviderNeverFallBack() {
        McpSessionFactory factory = factoryWith(new AtomicInteger());
        AgentScopeMcpProvider provider = new AgentScopeMcpProvider(factory);

        assertThrows(IllegalStateException.class, () -> provider.openScope(config(stdio()), workspace(true, null)));
        ScopeMcpProvider legacy = configuration -> {
            throw new AssertionError("local fallback");
        };
        assertThrows(IllegalStateException.class, () -> legacy.openScope(config(stdio()), workspace(true, null)));
        assertThrows(IllegalStateException.class,
                () -> McpWorkspaceSupport.prepare((McpConfig.Stdio) stdio().conf(), workspace(true, null)));
    }

    @Test
    void localWorkspaceIsNeverRejected() {
        McpWorkspaceSupport.validate(config(stdio()), workspace(false, null));
        McpWorkspaceSupport.validate(stdio(), workspace(false, null));
        assertThrows(IllegalStateException.class,
                () -> McpWorkspaceSupport.prepare((McpConfig.Stdio) stdio().conf(), workspace(false, null)));
    }

    @Test
    void isolatedPreparationUsesBridgeArgvAndClosesOnce() throws IOException {
        AtomicInteger closed = new AtomicInteger();
        WorkspaceStdioLauncher launcher = (command, env) -> {
            assertEquals(List.of("not-on-host-path", "hello world"), command);
            assertEquals(Map.of("VISIBLE", "yes"), env);
            return new WorkspaceStdioLaunch(List.of("sandbox-wrapper"), Map.of(), closed::incrementAndGet);
        };

        WorkspaceStdioLaunch launch = McpWorkspaceSupport.prepare((McpConfig.Stdio) stdio().conf(),
                workspace(true, new IsolatedStdioBridge(launcher)));
        McpOwnedStdioTransport transport = new McpOwnedStdioTransport(launch,
                new Jackson2McpJsonMapper(new ObjectMapper()));

        transport.closeGracefully().block(Duration.ofSeconds(5));
        transport.closeGracefully().block(Duration.ofSeconds(5));

        assertEquals(1, closed.get(), "the launch recipe must be released exactly once");
        assertTrue(transport.isClosed());
        assertThrows(IllegalStateException.class, () -> transport.connect(mono -> mono));
    }

    @Test
    void isolatedWorkspaceRejectsAnExplicitWorkDir() {
        McpConfig.MCP server = new McpConfig.MCP("stdio", null, McpTransport.STDIO, new McpConfig.Stdio(
                List.of("npx"), Map.of(), "D:/tools", INIT, EXEC), 100);

        assertThrows(IllegalArgumentException.class,
                () -> McpWorkspaceSupport.validate(config(server), workspace(true, null)));
    }

    /** Only stdio is allowed in isolation; an HTTP server would run outside the sandbox. */
    @Test
    void isolatedWorkspaceRejectsNonStdioTransports() {
        McpConfig.MCP server = new McpConfig.MCP("http", null, McpTransport.STREAMABLE_HTTP,
                new McpConfig.StreamableHttp("https://example.test/mcp", Map.of(), INIT, EXEC), 100);

        assertThrows(IllegalArgumentException.class,
                () -> McpWorkspaceSupport.validate(server, workspace(true, null)));
        assertFalse(McpWorkspaceSupport.isIsolated(workspace(false, null)));
        assertTrue(McpWorkspaceSupport.isIsolated(workspace(true, null)));    }

    private static McpSessionFactory factoryWith(AtomicInteger builds) {
        ObjectMapper json = new ObjectMapper();
        return new McpSessionFactory(McpClientSettings.defaults(),
                List.of(McpTestSupport.builderOf(() -> new FakeMcpTransport(), builds)), null, json);
    }

    private static McpConfig config(McpConfig.MCP... servers) {
        McpConfig config = new McpConfig();
        config.setMcp(List.of(servers));
        return config;
    }

    private static McpConfig.MCP stdio() {
        return new McpConfig.MCP("stdio", null, McpTransport.STDIO, new McpConfig.Stdio(
                List.of("not-on-host-path", "hello world"), Map.of("VISIBLE", "yes"), INIT, EXEC), 100);
    }

    private static Workspace workspace(boolean isolated, WorkspaceBridge bridge) {
        return new Workspace() {

            @Override
            public String id() {
                return "test";
            }

            @Override
            public String workDir() {
                return Path.of(".").toAbsolutePath().toString();
            }

            @Override
            public Path resolve(String path) {
                return Path.of(path);
            }

            @Override
            public RuntimeEnvironment runtimeEnvironment() {
                return RuntimeEnvironment.builder().isolated(isolated).build();
            }

            @Override
            public WorkspaceBridge bridge() {
                return bridge;
            }
        };
    }

    /**
     * The bridge of an isolated workspace also prepares the supervised stdio session; the policy
     * checks for that capability, so a launcher alone is not enough to stand in for it.
     */
    private static final class IsolatedStdioBridge implements WorkspaceBridge, WorkspaceStdioLauncher {

        private final WorkspaceStdioLauncher delegate;

        private IsolatedStdioBridge(WorkspaceStdioLauncher delegate) {
            this.delegate = delegate;
        }

        @Override
        public WorkspaceFiles files() {
            throw new UnsupportedOperationException("the policy never touches the files of the bridge");
        }

        @Override
        public WorkspaceCommands commands() {
            throw new UnsupportedOperationException("the policy never runs a command of the bridge");
        }

        @Override
        public WorkspaceStdioLaunch prepareStdio(List<String> command, Map<String, String> environment)
                throws IOException {
            return delegate.prepareStdio(command, environment);
        }
    }
}
