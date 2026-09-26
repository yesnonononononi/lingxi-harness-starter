package com.summit.harness.springbootautoconfigure.conf.mcp;

import com.summit.adapter.langchain4j.mcp.MCPProvider;
import com.summit.adapter.langchain4j.mcp.MCPToolConverter;
import com.summit.adapter.langchain4j.mcp.McpClientFactory;
import com.summit.core.mcp.McpProvider;
import com.summit.core.tool.ToolDefinition;
import com.summit.harness.springbootautoconfigure.properties.McpProperties;
import dev.langchain4j.mcp.client.McpClient;
import lombok.extern.slf4j.Slf4j;

import java.net.URI;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Owns only clients created from configuration; external provider beans keep their own lifecycle. */
@Slf4j
public class ConfiguredMcpProvider implements McpProvider, AutoCloseable {
    private final McpProperties properties;
    private final McpClientFactory factory;
    private final Map<String, McpClient> clients = new LinkedHashMap<>();
    private boolean closed;

    public ConfiguredMcpProvider(McpProperties properties, McpClientFactory factory) {
        this.properties = properties;
        this.factory = factory;
        properties.getServers().forEach((key, server) -> {
            if (server.isEnabled()) validate(key, server);
        });
    }

    @Override
    public synchronized List<ToolDefinition<?>> provide() {
        if (closed) throw new IllegalStateException("MCP provider is closed");
        List<ToolDefinition<?>> tools = new ArrayList<>();
        properties.getServers().forEach((key, server) -> {
            if (!server.isEnabled()) return;
            try {
                McpClient client = clients.computeIfAbsent(key, ignored -> factory.create(key,
                        server.getUrl(), server.getHeaders(), server.getInitializationTimeout(),
                        server.getExecutionTimeout()));
                long runtimeTimeout = (long) Math.ceil(server.getExecutionTimeout().toMillis() / 1000.0) + 5;
                String prefix = server.getToolNamePrefix() == null ? key + "_" : server.getToolNamePrefix();
                tools.addAll(new MCPProvider(List.of(client),
                        new MCPToolConverter(server.getMaxOutput(), runtimeTimeout, prefix)).provide());
            } catch (Exception failure) {
                // SDK exceptions can contain request headers/URLs. Log only the server key and type.
                log.warn("MCP server {} connection/discovery failed ({}); continuing without its tools",
                        key, failure.getClass().getSimpleName());
            }
        });
        return List.copyOf(tools);
    }

    @Override
    public synchronized void close() {
        if (closed) return;
        closed = true;
        clients.forEach((key, client) -> {
            try {
                client.close();
            } catch (Exception failure) {
                log.warn("MCP server {} close failed ({})", key, failure.getClass().getSimpleName());
            }
        });
        clients.clear();
    }

    private static void validate(String key, McpProperties.Server server) {
        if (!key.matches("[a-zA-Z0-9_-]+")) throw invalid(key, "invalid server key");
        if (!"streamable-http".equals(server.getTransport())) throw invalid(key, "unsupported transport");
        URI uri;
        try {
            uri = URI.create(server.getUrl());
        } catch (Exception e) {
            throw invalid(key, "url must be an absolute HTTP(S) URL");
        }
        if (!("http".equalsIgnoreCase(uri.getScheme()) || "https".equalsIgnoreCase(uri.getScheme()))
                || uri.getHost() == null || uri.getUserInfo() != null || uri.getFragment() != null) {
            throw invalid(key, "url must be an absolute HTTP(S) URL without user info or fragment");
        }
        if (server.getToolNamePrefix() != null && !server.getToolNamePrefix().matches("[a-zA-Z0-9_-]*")) {
            throw invalid(key, "invalid tool-name-prefix");
        }
        if (!positive(server.getInitializationTimeout()) || !positive(server.getExecutionTimeout())) {
            throw invalid(key, "timeouts must be at least 1ms");
        }
        if (server.getMaxOutput() <= 0) throw invalid(key, "max-output must be positive");
    }

    private static boolean positive(Duration duration) {
        return duration != null && duration.compareTo(Duration.ofMillis(1)) >= 0;
    }

    private static IllegalArgumentException invalid(String key, String reason) {
        return new IllegalArgumentException("Invalid MCP server '" + key + "': " + reason);
    }
}
