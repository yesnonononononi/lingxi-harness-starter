package com.summit.core.tool;

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

    /** Per-request switch copied from {@code AgentRuntimeParameters#isAllowOutsideWorkspace()}. */
    @Builder.Default
    private boolean allowOutsideWorkspace = false;
}
