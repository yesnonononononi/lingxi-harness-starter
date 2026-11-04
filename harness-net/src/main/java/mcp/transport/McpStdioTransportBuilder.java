package mcp.transport;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.summit.core.conf.McpConfig;
import com.summit.core.runtime.workspace.Workspace;
import com.summit.core.runtime.workspace.WorkspaceStdioLaunch;
import io.modelcontextprotocol.client.transport.ServerParameters;
import io.modelcontextprotocol.json.McpJsonMapper;
import io.modelcontextprotocol.spec.McpClientTransport;
import mcp.McpClientTransportBuilder;
import mcp.McpWorkspaceSupport;
import mcp.json.Jackson2McpJsonMapper;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

/**
 * Builds stdio transports: a host process started from the configured argv, or — inside an isolated
 * workspace — a sandbox-supervised process that owns its own launch recipe.
 *
 * <p>The executable is always resolved through {@link WindowsCommandResolver} first, otherwise
 * {@code npx}-style commands cannot be started on Windows at all. The environment is merged on top of
 * the SDK's inherited whitelist rather than replacing it, so a server still sees {@code PATH}.</p>
 */
public final class McpStdioTransportBuilder implements McpClientTransportBuilder {

    private final McpJsonMapper jsonMapper;

    /**
     * @param objectMapper application mapper used for the JSON-RPC framing; the SDK's ServiceLoader
     *                     lookup is deliberately bypassed
     */
    public McpStdioTransportBuilder(ObjectMapper objectMapper) {
        this.jsonMapper = new Jackson2McpJsonMapper(objectMapper);
    }

    @Override
    public boolean supports(McpConfig.Conf conf) {
        return conf instanceof McpConfig.Stdio;
    }

    @Override
    public McpClientTransport build(McpConfig.MCP server, Workspace workspace) {
        if (server == null || !(server.conf() instanceof McpConfig.Stdio stdio)) {
            throw new IllegalArgumentException(
                    "MCP server '" + (server == null ? "null" : server.name())
                            + "': stdio transport requires stdio connection settings");
        }
        if (McpWorkspaceSupport.isIsolated(workspace)) {
            return ownedTransport(server.name(), stdio, workspace);
        }
        return new McpStdioTransport(parametersOf(server.name(), stdio), jsonMapper, workDirOf(stdio));
    }

    /** One sandbox session per transport: a reconnect prepares a fresh recipe instead of reusing a closed one. */
    private McpClientTransport ownedTransport(String serverName, McpConfig.Stdio stdio, Workspace workspace) {
        WorkspaceStdioLaunch launch;
        try {
            launch = McpWorkspaceSupport.prepare(stdio, workspace);
        } catch (IOException failure) {
            throw new UncheckedIOException(
                    "Failed to prepare isolated MCP stdio session: " + serverName, failure);
        }
        return new McpOwnedStdioTransport(launch, jsonMapper);
    }

    private ServerParameters parametersOf(String serverName, McpConfig.Stdio stdio) {
        List<String> command = WindowsCommandResolver.resolve(stdio.command());
        if (command == null || command.isEmpty()) {
            throw new IllegalArgumentException("MCP server '" + serverName + "': command must not be empty");
        }
        return ServerParameters.builder(command.get(0))
                .args(command.subList(1, command.size()))
                .env(orEmpty(stdio.env()))
                .build();
    }

    /**
     * A configured working directory keeps a server that resolves relative paths inside its own tree.
     *
     * @return the directory to start the child in, or null to inherit the host process directory
     */
    private Path workDirOf(McpConfig.Stdio stdio) {
        if (stdio.workDir() == null || stdio.workDir().isBlank()) {
            return null;
        }
        return Path.of(stdio.workDir().trim());
    }

    private static Map<String, String> orEmpty(Map<String, String> values) {
        return values == null ? Map.of() : Map.copyOf(values);
    }
}
