package com.summit.adapter.langchain4j.mcp;

import com.summit.core.mcp.McpSession;
import com.summit.core.tool.ToolDefinition;
import com.summit.core.tool.ToolExecutor;
import dev.langchain4j.agent.tool.ToolSpecification;
import dev.langchain4j.mcp.client.McpClient;
import lombok.RequiredArgsConstructor;

import java.util.ArrayList;
import java.util.List;

/** One langchain4j {@link McpClient} bound to the lifetime of a request. */
@RequiredArgsConstructor
public class Langchain4jMcpSession implements McpSession {

    private final String name;
    private final McpClient client;
    private final MCPToolConverter converter;

    /** Connects, then discovers and maps this server's tools. Throws when the server is unreachable. */
    public static Langchain4jMcpSession connect(String name, McpClient client, MCPToolConverter converter) {
        return new Langchain4jMcpSession(name, client, converter);
    }

    @Override
    public String name() {
        return name;
    }

    @Override
    public List<ToolDefinition<? extends ToolExecutor>> tools() {
        List<ToolDefinition<? extends ToolExecutor>> tools = new ArrayList<>();
        try {
            for (ToolSpecification specification : client.listTools()) {
                tools.add(converter.convert(client, specification));
            }
        } catch (Exception failure) {
            throw new IllegalStateException("Failed to list the tools of MCP server '" + name + "'", failure);
        }
        return List.copyOf(tools);
    }

    @Override
    public void close() {
        try {
            client.close();
        } catch (Exception failure) {
            throw new IllegalStateException("Failed to close MCP client of server '" + name + "'", failure);
        }
    }
}
