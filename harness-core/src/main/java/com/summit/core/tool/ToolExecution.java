package com.summit.core.tool;

import com.summit.core.mcp.McpToolScope;
import com.summit.core.runtime.workspace.Workspace;

import lombok.Builder;
import lombok.Data;

import java.util.Map;

@Builder
@Data
public class ToolExecution {
    private String id;
    private ToolDefinition<? extends ToolExecutor> toolDefinition;
    private String args;
    private final String executionId;
    /** Id of the agent request (turn) this tool call belongs to. */
    private String turnId;
    private Workspace workspace;
    /** Opaque attributes carried from the originating {@code AgentRequest} down to the tool. */
    @Builder.Default
    private Map<String, Object> attributes = Map.of();

    /** Explicitly selected event metadata of the originating execution. */
    @Builder.Default
    private Map<String, Object> eventMetaData = Map.of();

    public Map<String, Object> getEventMetaData() {
        return eventMetaData == null ? Map.of() : Map.copyOf(eventMetaData);
    }

    /** Per-request switch copied from {@code AgentRuntimeParameters#isAllowOutsideWorkspace()}. */
    @Builder.Default
    private boolean allowOutsideWorkspace = false;

    /**
     * The MCP tools declared by this call's request, for a tool that must enumerate the callable
     * surface of its own run. The process-wide registry holds only the static layer.
     */
    private McpToolScope mcpToolScope;

    /**
     * The MCP tools of this call's request, never {@code null}.
     *
     * <p>Named differently from the field on purpose: {@code @Builder} derives its setter from the
     * field, and a same-named method becomes a second candidate it can bind to instead.</p>
     */
    public McpToolScope requireMcpToolScope() {
        return mcpToolScope == null ? McpToolScope.EMPTY : mcpToolScope;
    }
}
