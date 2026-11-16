package com.summit.core.tool;

import com.summit.core.conf.SkillConfig;
import com.summit.core.conversation.api.ToolCallRequest;
import com.summit.core.mcp.McpToolScope;
import com.summit.core.runtime.loop.ExecutionControlSignal;
import com.summit.core.runtime.workspace.Workspace;
import com.summit.core.workspace.WorkspaceOptions;
import lombok.Builder;

import java.util.List;
import java.util.Map;

/**
 * A batch of tool calls for one agent turn.
 */

@Builder
public record ToolExecuteCommand(List<ToolCallRequest> requests,
                                 String executionId,
                                 Workspace workspace,
                                 Map<String, Object> attributes,
                                 Map<String, Object> eventMetaData,
                                 List<String> allowedTools,
                                 String responseId,
                                 boolean allowOutsideWorkspace,
                                 McpToolScope mcpToolScope,
                                 SkillConfig skillConfig,
                                 WorkspaceOptions workspaceOptions,
                                 ExecutionControlSignal signal
) {


    public ToolExecuteCommand {
        attributes = attributes == null ? Map.of() : Map.copyOf(attributes);
        eventMetaData = eventMetaData == null ? Map.of() : Map.copyOf(eventMetaData);
        skillConfig = skillConfig == null ? null : new SkillConfig(skillConfig.getPath());
    }

    /**
     * The MCP tools of the originating request, never {@code null}.
     */
    public McpToolScope mcpToolScope() {
        return mcpToolScope == null ? McpToolScope.EMPTY : mcpToolScope;
    }

    /** This batch with its calls replaced; every other field is carried over unchanged. */
    public ToolExecuteCommand withRequests(List<ToolCallRequest> newRequests) {
        return new ToolExecuteCommand(newRequests, executionId, workspace, attributes, eventMetaData, allowedTools,
                responseId, allowOutsideWorkspace, mcpToolScope, skillConfig, workspaceOptions, signal);
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
