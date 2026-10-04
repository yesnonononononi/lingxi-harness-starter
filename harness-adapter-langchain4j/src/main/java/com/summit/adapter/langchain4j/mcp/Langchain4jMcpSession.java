package com.summit.adapter.langchain4j.mcp;

import com.summit.core.mcp.McpSession;
import com.summit.core.mcp.McpSessionType;
import com.summit.core.tool.ToolDefinition;
import com.summit.core.tool.ToolExecutor;
import dev.langchain4j.agent.tool.ToolSpecification;
import dev.langchain4j.mcp.client.McpClient;
import lombok.RequiredArgsConstructor;

import java.util.ArrayList;
import java.util.List;

/**
 * One langchain4j {@link McpClient} bound to a lifetime chosen by its {@link McpSessionType}.
 *
 * <p>The underlying client connects lazily: building the session performs no I/O, the first
 * {@link #tools()} call starts the transport and the MCP handshake. A session may therefore be
 * created long before it is used, and a {@link McpSessionType#REUSE} session created up front is
 * connected by whichever request reaches it first and reused by the rest.</p>
 */
@RequiredArgsConstructor
public class Langchain4jMcpSession implements McpSession {

    private final String name;
    private final String description;
    private final McpClient client;
    private final MCPToolConverter converter;
    private final McpSessionType type;

    /** Creates a request-owned session, the common case. */
    public static Langchain4jMcpSession connect(String name, String description, McpClient client,
                                                MCPToolConverter converter) {
        return connect(name, description, client, converter, McpSessionType.PER_REQUEST);
    }

    /** Creates a session with an explicit lifetime contract, e.g. a shared {@code REUSE} pool. */
    public static Langchain4jMcpSession connect(String name, String description, McpClient client,
                                                MCPToolConverter converter, McpSessionType type) {
        return new Langchain4jMcpSession(name, description, client, converter, type);
    }

    @Override
    public String name() {
        return name;
    }

    /** The description declared by the request configuration, never null. */
    @Override
    public String description() {
        return description == null ? "" : description;
    }

    @Override
    public McpSessionType type() {
        return type;
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
    public void checkHealth() {
        client.checkHealth();
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
