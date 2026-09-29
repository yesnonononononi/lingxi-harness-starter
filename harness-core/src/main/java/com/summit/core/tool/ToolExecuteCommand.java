package com.summit.core.tool;

import com.summit.core.conversation.api.ToolCallRequest;
import com.summit.core.mcp.McpToolScope;
import com.summit.core.runtime.workspace.Workspace;

import java.util.List;
import java.util.Map;

/** A batch of tool calls for one agent turn. */
public record ToolExecuteCommand(List<ToolCallRequest> requests, String executionId,
                                 Workspace workspace, Map<String, Object> attributes,
                                 List<String> allowedTools,
                                 boolean allowOutsideWorkspace,
                                 McpToolScope mcpToolScope) {

    /** Backwards-compatible form: assumes operations stay inside the workspace. */
    public ToolExecuteCommand(List<ToolCallRequest> requests, String executionId,
                              Workspace workspace, Map<String, Object> attributes,
                              List<String> allowedTools) {
        this(requests, executionId, workspace, attributes, allowedTools, false, null);
    }

    public ToolExecuteCommand {
        attributes = attributes == null ? Map.of() : Map.copyOf(attributes);
    }

    /** The MCP tools of the originating request, never {@code null}. */
    public McpToolScope mcpToolScope() {
        return mcpToolScope == null ? McpToolScope.EMPTY : mcpToolScope;
    }

    /**
     * Resolves a tool of this batch: the static registry first, then the request's MCP scope. The
     * static layer wins so a request-level MCP tool cannot shadow a framework built-in.
     */
    public ToolDefinition<?> resolve(ToolRegistry registry, String toolName) {
        ToolDefinition<?> registered = registry.getTool(toolName);
        return registered != null ? registered : mcpToolScope().getTool(toolName);
    }
}
