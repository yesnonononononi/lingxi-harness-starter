package com.summit.adapter.langchain4j.mcp;

import com.summit.core.conf.McpConfig;
import dev.langchain4j.mcp.client.DefaultMcpClient;
import dev.langchain4j.mcp.client.McpClient;
import dev.langchain4j.mcp.client.transport.McpTransport;
import dev.langchain4j.mcp.client.transport.http.StreamableHttpMcpTransport;
import dev.langchain4j.mcp.client.transport.stdio.StdioMcpTransport;

import java.util.List;
import java.util.Map;

/**
 * Creates owned MCP connections; the client transport is chosen by the server's {@code conf}
 * type. Client construction starts the transport and completes protocol initialization before returning. Applications can replace this factory.
 */
public class McpClientFactory {

    public McpClient create(String key, McpConfig.MCP server) {
        McpTransport transport = transportOf(key, server.conf());
        try {
            return new DefaultMcpClient.Builder()
                    .key(key)
                    .transport(transport)
                    .initializationTimeout(server.initializationTimeout())
                    .toolExecutionTimeout(server.executionTimeout())
                    // A request's scope lives only as long as the request, so change notifications
                    // have no live consumer. Leaving the defaults on sends a subscriptions/listen
                    // call that most servers answer with 404, logging a stack trace per server.
                    .subscribeToToolListChanges(false)
                    .subscribeToPromptListChanges(false)
                    .subscribeToResourceListChanges(false)
                    .build();
        } catch (RuntimeException | Error failure) {
            try {
                transport.close();
            } catch (Exception closeFailure) {
                failure.addSuppressed(closeFailure);
            }
            throw failure;
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
            case McpConfig.Stdio stdio -> StdioMcpTransport.builder()
                    .command(WindowsCommandResolver.resolve(List.copyOf(stdio.command())))
                    .environment(orEmpty(stdio.env()))
                    .logEvents(false)
                    .build();
        };
    }

    private static Map<String, String> orEmpty(Map<String, String> values) {
        return values == null ? Map.of() : Map.copyOf(values);
    }
}
