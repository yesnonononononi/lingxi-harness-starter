package com.summit.core.tool;

import com.summit.core.conversation.api.ToolCallRequest;
import com.summit.core.conf.SkillConfig;
import com.summit.core.mcp.McpToolScope;
import com.summit.core.runtime.workspace.Workspace;

import java.util.List;
import java.util.Map;
import java.util.UUID;

/** A batch of tool calls for one agent turn. */
public record ToolExecuteCommand(List<ToolCallRequest> requests, String executionId,
                                 Workspace workspace,
                                 Map<String, Object> attributes,
                                 Map<String,Object> eventMetaData,
                                 List<String> allowedTools,
                                 UUID responseId,
                                 boolean allowOutsideWorkspace,
                                 McpToolScope mcpToolScope,
                                 SkillConfig skillConfig
) {

    public ToolExecuteCommand(List<ToolCallRequest> requests, String executionId,
                              Workspace workspace, Map<String, Object> attributes,
                              Map<String, Object> eventMetaData, List<String> allowedTools,
                              UUID responseId,
                              boolean allowOutsideWorkspace, McpToolScope mcpToolScope) {
        this(requests, executionId, workspace, attributes, eventMetaData, allowedTools,responseId,
                allowOutsideWorkspace, mcpToolScope, null);
    }

    /** Backwards-compatible form: assumes operations stay inside the workspace. */
    public ToolExecuteCommand(List<ToolCallRequest> requests, String executionId,
                              Workspace workspace, Map<String, Object> attributes,
                              List<String> allowedTools,UUID responseId) {
        this(requests, executionId, workspace, attributes, null, allowedTools,responseId, false, null);
    }

    /** Compatibility overload for callers without event metadata. */
    public ToolExecuteCommand(List<ToolCallRequest> requests, String executionId,
                              Workspace workspace, Map<String, Object> attributes,
                              List<String> allowedTools,UUID responseId,
                              boolean allowOutsideWorkspace,
                              McpToolScope mcpToolScope) {
        this(requests, executionId, workspace, attributes, Map.of(), allowedTools, responseId,
                allowOutsideWorkspace, mcpToolScope);
    }

    public ToolExecuteCommand {
        attributes = attributes == null ? Map.of() : Map.copyOf(attributes);
        eventMetaData = eventMetaData == null ? Map.of() : Map.copyOf(eventMetaData);
        skillConfig = skillConfig == null ? null : new SkillConfig(skillConfig.getPath());
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
