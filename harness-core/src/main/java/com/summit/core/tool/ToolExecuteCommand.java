package com.summit.core.tool;

import com.summit.core.conversation.api.ToolCallRequest;
import com.summit.core.runtime.workspace.Workspace;

import java.util.List;
import java.util.Map;

/** A batch of tool calls for one agent turn. */
public record ToolExecuteCommand(List<ToolCallRequest> requests, String executionId,
                                 Workspace workspace, Map<String, Object> attributes,
                                 List<String> allowedTools,
                                 boolean allowOutsideWorkspace) {

    /** Backwards-compatible form: assumes operations stay inside the workspace. */
    public ToolExecuteCommand(List<ToolCallRequest> requests, String executionId,
                              Workspace workspace, Map<String, Object> attributes,
                              List<String> allowedTools) {
        this(requests, executionId, workspace, attributes, allowedTools, false);
    }

    public ToolExecuteCommand {
        attributes = attributes == null ? Map.of() : Map.copyOf(attributes);
    }
}
