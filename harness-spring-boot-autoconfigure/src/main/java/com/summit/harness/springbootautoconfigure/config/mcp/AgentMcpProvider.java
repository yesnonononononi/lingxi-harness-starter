package com.summit.harness.springbootautoconfigure.config.mcp;

import com.summit.adapter.langchain4j.mcp.Langchain4jMcpSession;
import com.summit.adapter.langchain4j.mcp.MCPToolConverter;
import com.summit.adapter.langchain4j.mcp.McpClientFactory;
import com.summit.adapter.langchain4j.mcp.McpValidator;
import com.summit.core.conf.McpConfig;
import com.summit.core.mcp.McpProvider;
import com.summit.core.mcp.McpSession;
import com.summit.core.mcp.McpToolScope;
import dev.langchain4j.mcp.client.McpClient;
import lombok.NonNull;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Opens one session per reachable MCP server of the request's configuration.
 *
 * <p>Connections are not shared or cached: they belong to the request that declared them and are
 * released when its {@link McpToolScope} closes. A server that cannot be reached is skipped, so a
 * broken endpoint only removes its own tools instead of failing the whole request.</p>
 */
@Slf4j
@RequiredArgsConstructor
public class AgentMcpProvider implements McpProvider {

    /** Prefix separating remote tool names from the framework's own. */
    public static final String NAME_MCP_PREFIX = "mcp_";

    private static final long RUNTIME_TIMEOUT_GRACE_SECONDS = 5L;

    private final McpClientFactory factory;

    @Override
    public McpToolScope openScope(@NonNull McpConfig mcpConfig) {
        List<McpConfig.MCP> servers = mcpConfig.getMcp();
        if (servers == null || servers.isEmpty()) {
            return McpToolScope.EMPTY;
        }
        List<McpSession> sessions = new ArrayList<>();
        for (McpConfig.MCP server : servers) {
            McpSession session = openSession(server);
            if (session != null) {
                sessions.add(session);
            }
        }
        return McpToolScope.of(sessions);
    }

    /** @return the session of one server, or {@code null} when it is invalid or unreachable. */
    private McpSession openSession(McpConfig.MCP server) {
        McpClient client = null;
        try {
            McpValidator.validate(server);
            client = factory.create(server.name(), server);

            return Langchain4jMcpSession.connect(server.name(), client, converterOf(server));
        } catch (Exception failure) {
            closeQuietly(client, server.name());
            logConnectionFailure(server, failure);
            return null;
        }
    }

    /**
     * Reports why one server contributed no tools, including the transport and the root cause.
     * Without them "connection failed (RuntimeException)" cannot distinguish an unreachable
     * endpoint from a launch command the OS refused to start. Only the root cause's message is
     * logged — outer transport exceptions embed headers and URLs, which hold tokens.
     */
    private void logConnectionFailure(McpConfig.MCP server, Exception failure) {
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

    /** The runtime timeout tolerates the transport's own before giving up, so a slow remote call
     * is still reported by the server rather than the local guard. */
    private MCPToolConverter converterOf(McpConfig.MCP server) {
        long runtimeTimeout = (long) Math.ceil(server.executionTimeout().toMillis() / 1000.0)
                + RUNTIME_TIMEOUT_GRACE_SECONDS;
        String prefix = NAME_MCP_PREFIX
                + (server.toolNamePrefix() == null ? server.name() + "_" : server.toolNamePrefix());
        return new MCPToolConverter(server.maxOutput(), runtimeTimeout, prefix);
    }

    private void closeQuietly(McpClient client, String serverName) {
        if (client == null) return;
        try {
            client.close();
        } catch (Exception failure) {
            log.warn("MCP server {} close failed ({})", serverName, failure.getClass().getSimpleName());
        }
    }
}
