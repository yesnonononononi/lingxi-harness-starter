package com.summit.core.mcp;

import com.summit.core.tool.ToolDefinition;

import java.util.List;

/**
 * SPI supplying the tools of the connected MCP server(s).
 *
 * <p>Reuses the harness tool model completely: an implementation only has to list the remote tools
 * and map each of them to a {@link ToolDefinition} whose executor is an {@link McpToolExecutor}.
 * From there the tools flow through the ordinary registry / model-request / execution path, so no
 * MCP-specific execution machinery is required.</p>
 */
public interface McpProvider {
   List<ToolDefinition<?>> provide();
}
