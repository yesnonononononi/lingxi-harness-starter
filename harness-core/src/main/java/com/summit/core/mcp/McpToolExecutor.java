package com.summit.core.mcp;

import com.summit.core.tool.ToolExecutor;

/**
 * Executor of a tool hosted by an MCP server.
 *
 * <p>Reuses the harness tool SPI: {@link com.summit.core.tool.ToolExecution} already carries the
 * tool name, the model arguments as a JSON string, the call id and the workspace, while
 * {@link com.summit.core.tool.ToolExecuteResult} is the single result type of the runtime.
 * Timeout, output truncation and exception-to-error conversion stay with the surrounding runtime,
 * so an implementation only decodes the arguments, calls the MCP server and builds the result.</p>
 */
public interface McpToolExecutor extends ToolExecutor {
}
