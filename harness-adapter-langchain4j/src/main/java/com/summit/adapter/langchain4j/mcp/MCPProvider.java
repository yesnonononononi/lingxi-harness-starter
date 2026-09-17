package com.summit.adapter.langchain4j.mcp;

import com.summit.core.mcp.McpProvider;
import com.summit.core.tool.ToolDefinition;
import dev.langchain4j.agent.tool.ToolSpecification;
import dev.langchain4j.mcp.client.McpClient;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Exposes the tools of every connected MCP server as core tool definitions.
 *
 * <p>The clients are owned outside of this provider (they are long-lived connections with their own
 * health check and reconnection), so {@link #provide()} is a read-only snapshot: it may be called
 * again — for instance after a reconnect or a tool list change notification — to refresh what the
 * registry holds.</p>
 */
@Slf4j
@RequiredArgsConstructor
public class MCPProvider implements McpProvider {

    private final List<McpClient> mcpClients;
    private final MCPToolConverter converter;

    public MCPProvider(List<McpClient> mcpClients) {
        this(mcpClients, new MCPToolConverter());
    }

    @Override
    public List<ToolDefinition<?>> provide() {
        Map<String, ToolDefinition<?>> tools = new LinkedHashMap<>();
        for (McpClient mcpClient : mcpClients) {
            String clientKey = clientKey(mcpClient);
            try {
                for (ToolSpecification toolSpecification : mcpClient.listTools()) {
                    ToolDefinition<?> definition = converter.convert(mcpClient, toolSpecification);
                    if (tools.putIfAbsent(definition.name(), definition) != null) {
                        log.warn("【MCP】tool {} of mcp server {} is ignored, another server already provides it",
                                definition.name(), clientKey);
                    }
                }
            } catch (Exception e) {
                // a broken server must not hide the tools of the others
                log.warn("【MCP】failed to list the tools of mcp server {}", clientKey, e);
            }
        }
        log.info("【MCP】{} tool(s) provided by {} mcp server(s)", tools.size(), mcpClients.size());
        return List.copyOf(tools.values());
    }

    private String clientKey(McpClient mcpClient) {
        try {
            return mcpClient.key();
        } catch (Exception e) {
            return "unknown";
        }
    }
}
