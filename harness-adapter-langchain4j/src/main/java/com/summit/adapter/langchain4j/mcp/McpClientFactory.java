package com.summit.adapter.langchain4j.mcp;

import com.summit.core.conf.McpConfig;
import com.summit.core.runtime.workspace.Workspace;
import dev.langchain4j.mcp.client.DefaultMcpClient;
import dev.langchain4j.mcp.client.McpClient;
import dev.langchain4j.mcp.client.transport.McpTransport;
import dev.langchain4j.mcp.client.transport.http.StreamableHttpMcpTransport;
import dev.langchain4j.mcp.client.transport.stdio.StdioMcpTransport;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Map;

/**
 * Creates owned MCP connections; the client transport is chosen by the server's {@code conf}
 * type. Client construction starts the transport and completes protocol initialization before returning. Applications can replace this factory.
 */
public class McpClientFactory {

    public McpClient create(String key, McpConfig.MCP server) {
        return createClient(key, server, transportOf(key, server.conf()));
    }

    public McpClient create(String key, McpConfig.MCP server, Workspace workspace) {
        if (!McpWorkspaceAdapter.isolated(workspace)) return create(key, server);
        if (!(server.conf() instanceof McpConfig.Stdio stdio)) {
            throw new IllegalArgumentException("Isolated workspace permits only stdio MCP: " + key);
        }
        try {
            return createClient(key, server, new OwnedStdioMcpTransport(McpWorkspaceAdapter.prepare(stdio, workspace)));
        } catch (IOException failure) {
            throw new UncheckedIOException("Failed to prepare isolated MCP: " + key, failure);
        }
    }

    private McpClient createClient(String key, McpConfig.MCP server, McpTransport transport) {
        try {
            DefaultMcpClient.Builder builder = new DefaultMcpClient.Builder()
                    .key(key)
                    .transport(transport)
                    .initializationTimeout(server.initializationTimeout())
                    .toolExecutionTimeout(server.executionTimeout())
                    // A request's scope lives only as long as the request, so change notifications
                    // have no live consumer. Leaving the defaults on sends a subscriptions/listen
                    // call that most servers answer with 404, logging a stack trace per server.
                    .subscribeToToolListChanges(false)
                    .subscribeToPromptListChanges(false)
                    .subscribeToResourceListChanges(false);
            applyTuning(builder, server.conf());
            return builder.build();
        } catch (RuntimeException | Error failure) {
            try {
                transport.close();
            } catch (Exception closeFailure) {
                failure.addSuppressed(closeFailure);
            }
            throw failure;
        }
    }

    /**
     * Forwards the client-tuning settings carried by either connection record.
     *
     * <p>Both transports go through this single method: duplicating the four branches per record is
     * exactly the kind of duplication that lets a field silently stop being forwarded.
     *
     * @param builder client builder to tune
     * @param conf    connection settings of the server being created
     */
    void applyTuning(DefaultMcpClient.Builder builder, McpConfig.Conf conf) {
        switch (conf) {
            case McpConfig.StreamableHttp http -> applyTuning(builder, http.protocolVersion(),
                    http.autoHealthCheck(), http.healthCheckInterval(), http.reconnectInterval());
            case McpConfig.Stdio stdio -> applyTuning(builder, stdio.protocolVersion(),
                    stdio.autoHealthCheck(), stdio.healthCheckInterval(), stdio.reconnectInterval());
            // The bundled client library ships no SSE transport; the validator rejects it first.
            case McpConfig.Sse ignored -> {
            }
        }
    }

    /**
     * Applies the optional client-tuning settings shared by every transport.
     *
     * <p>Each value is set only when the configuration provides one: a {@code null} field means the
     * client library default stays in force, which keeps configurations written before these
     * fields existed on exactly their previous behaviour. Values are therefore never defaulted
     * here — only forwarded.
     *
     * @param builder             client builder to tune
     * @param protocolVersion     explicit protocol version; blank or null keeps library probing
     * @param autoHealthCheck     health check switch; null keeps the library default
     * @param healthCheckInterval health check interval; null keeps the library default
     * @param reconnectInterval   reconnect interval; null keeps the library default
     */
    void applyTuning(DefaultMcpClient.Builder builder, String protocolVersion,
                     Boolean autoHealthCheck, Duration healthCheckInterval,
                     Duration reconnectInterval) {
        if (protocolVersion != null && !protocolVersion.isBlank()) {
            builder.protocolVersion(protocolVersion.trim());
        }
        if (autoHealthCheck != null) {
            builder.autoHealthCheck(autoHealthCheck);
        }
        if (healthCheckInterval != null) {
            builder.autoHealthCheckInterval(healthCheckInterval);
        }
        if (reconnectInterval != null) {
            builder.reconnectInterval(reconnectInterval);
        }
    }

    private McpTransport transportOf(String key, McpConfig.Conf conf) {
        return switch (conf) {
            case McpConfig.StreamableHttp http -> StreamableHttpMcpTransport.builder()
                    .url(http.url())
                    .customHeaders(orEmpty(http.headers()))
                    .logRequests(false)
                    .logResponses(false)
                    .build();
            // The bundled client library ships no SSE transport; the validator rejects it first.
            case McpConfig.Sse ignored -> throw new IllegalArgumentException(
                    "MCP server '" + key + "': SSE transport is not supported; use streamable-http");
            case McpConfig.Stdio stdio -> stdioTransport(stdio);
        };
    }

    /** A configured working directory keeps a server that resolves relative paths inside its own tree. */
    private StdioMcpTransport stdioTransport(McpConfig.Stdio stdio) {
        StdioMcpTransport.Builder builder = StdioMcpTransport.builder()
                .command(WindowsCommandResolver.resolve(List.copyOf(stdio.command())))
                .environment(orEmpty(stdio.env()))
                .logEvents(false);
        if (stdio.workDir() != null && !stdio.workDir().isBlank()) {
            builder.workingDirectory(Path.of(stdio.workDir()));
        }
        return builder.build();
    }

    private static Map<String, String> orEmpty(Map<String, String> values) {
        return values == null ? Map.of() : Map.copyOf(values);
    }
}
