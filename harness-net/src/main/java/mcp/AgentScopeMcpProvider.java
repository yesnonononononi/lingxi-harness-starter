package mcp;

import com.summit.core.conf.McpConfig;
import com.summit.core.mcp.McpSession;
import com.summit.core.mcp.McpSessionType;
import com.summit.core.mcp.McpToolScope;
import com.summit.core.mcp.ScopeMcpProvider;
import com.summit.core.runtime.workspace.Workspace;
import lombok.NonNull;
import lombok.extern.slf4j.Slf4j;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Opens one session per reachable MCP server of the request's configuration.
 *
 * <p>Connections are not shared or cached: they belong to the request that declared them and are
 * released when its {@link McpToolScope} closes. A server that cannot be reached is skipped, so a
 * broken endpoint only removes its own tools instead of failing the whole request — the caller's
 * remaining servers keep working, which is what makes an expired token on one integration harmless
 * to a conversation.</p>
 */
@Slf4j
public final class AgentScopeMcpProvider implements ScopeMcpProvider {

    /**
     * Prefix separating remote tool names from the framework's own. Fixed: a tool is published as
     * {@code mcp_} plus the name the server gave it, so neither the server name nor anything the
     * caller configured reaches the model-facing function name.
     */
    public static final String NAME_MCP_PREFIX = "mcp_";

    private final McpSessionFactory factory;

    /**
     * @param factory factory every connection of this provider goes through
     */
    public AgentScopeMcpProvider(McpSessionFactory factory) {
        if (factory == null) {
            throw new IllegalArgumentException("MCP session factory must not be null");
        }
        this.factory = factory;
    }

    @Override
    public McpToolScope openScope(@NonNull McpConfig mcpConfig) {
        return openScope(mcpConfig, null);
    }

    @Override
    public McpToolScope openScope(@NonNull McpConfig mcpConfig, Workspace workspace) {
        // Policy first: an isolated workspace either accepts the whole configuration or nothing
        // starts, so a mixed configuration cannot leave a host-side process running.
        McpWorkspaceSupport.validate(mcpConfig, workspace);
        List<McpConfig.MCP> servers = mcpConfig.getMcp();
        if (servers == null || servers.isEmpty()) {
            return McpToolScope.EMPTY;
        }

        List<McpSession> sessions = new ArrayList<>();
        for (McpConfig.MCP server : servers) {
            McpSession session = openSession(server, workspace);
            if (session != null) {
                sessions.add(session);
            }
        }
        return McpToolScope.of(sessions);
    }

    /** @return the session of one server, or {@code null} when it is invalid or unreachable. */
    private McpSession openSession(McpConfig.MCP server, Workspace workspace) {
        try {
            return factory.openSession(server, workspace, McpSessionType.PER_REQUEST, NAME_MCP_PREFIX);
        } catch (RuntimeException | Error failure) {
            logConnectionFailure(server, failure);
            return null;
        }
    }

    /**
     * Reports why one server contributed no tools, including the transport and the root cause.
     * Without them "connection failed (RuntimeException)" cannot distinguish an unreachable endpoint
     * from a launch command the OS refused to start. Only the root cause's message is logged — outer
     * transport exceptions embed headers and URLs, which hold tokens.
     */
    private void logConnectionFailure(McpConfig.MCP server, Throwable failure) {
        Throwable root = rootCauseOf(failure);
        log.warn("MCP server {} ({}) connection failed ({}): {}; continuing without its tools",
                server.name(), describeConf(server), root.getClass().getSimpleName(), root.getMessage());
    }

    /**
     * Renders the connection settings for a log line, keeping credentials out: headers and env are
     * reduced to their key names. The stdio argv is kept in full — a start-up failure is about the
     * command, and credentials travel through {@code env}, not argv.
     */
    private String describeConf(McpConfig.MCP server) {
        return switch (server.conf()) {
            case McpConfig.StreamableHttp http -> "streamable-http url=" + http.url()
                    + ", headers=" + keyNamesOf(http.headers());
            case McpConfig.Sse sse -> "sse url=" + sse.url();
            case McpConfig.Stdio stdio -> "stdio command=" + stdio.command()
                    + ", env=" + keyNamesOf(stdio.env());
        };
    }

    private static String keyNamesOf(Map<String, String> values) {
        return values == null || values.isEmpty() ? "[]" : values.keySet().toString();
    }

    /** Unwraps the wrapper exceptions the transports add, so the OS-level reason is reached. */
    private static Throwable rootCauseOf(Throwable failure) {
        Throwable current = failure;
        while (current.getCause() != null && current.getCause() != current) {
            current = current.getCause();
        }
        return current;
    }
}
