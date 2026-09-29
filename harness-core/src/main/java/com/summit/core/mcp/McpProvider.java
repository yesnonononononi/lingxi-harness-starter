package com.summit.core.mcp;

import com.summit.core.conf.McpConfig;
import com.summit.core.tool.ToolDefinition;
import lombok.NonNull;

/**
 * SPI opening the MCP sessions of one request.
 *
 * <p>Reuses the harness tool model completely: an implementation connects to the servers named by
 * the configuration, discovers their tools and maps each of them to a {@link ToolDefinition} whose
 * executor is an {@link McpToolExecutor}. From there the tools flow through the ordinary
 * model-request / execution path, so no MCP-specific execution machinery is required.</p>
 *
 * <p>The returned scope belongs to the calling request. A server that cannot be reached is skipped
 * rather than failing the request: its absence only removes its tools from the run.</p>
 */
public interface McpProvider {

    /** Opens the sessions declared by the given configuration. Never returns {@code null}. */
    McpToolScope openScope(@NonNull McpConfig mcpConfig);
}
