package com.summit.core.mcp;

import com.summit.core.tool.ToolDefinition;
import com.summit.core.tool.ToolExecutor;

import java.util.List;

public interface McpProvider {
    List<ToolDefinition<? extends ToolExecutor>> provide();
}
