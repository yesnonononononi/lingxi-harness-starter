package com.summit.runtime.tool;

import com.summit.core.tool.KernelTools;
import com.summit.core.tool.ToolDefinition;
import com.summit.core.tool.ToolExecuteResult;
import com.summit.core.tool.ToolExecution;
import com.summit.core.tool.ToolExecutionPolicy;
import com.summit.core.workspace.WorkspaceAccessMode;
import com.summit.core.workspace.WorkspaceOptions;

/**
 * Enforces {@link WorkspaceAccessMode#READ_ONLY} before tool execution so callers can keep
 * {@code AgentRequest.toolList} stable for prefix caching while blocking non-read-only tools at runtime.
 */
public class WorkspaceAccessToolPolicy implements ToolExecutionPolicy {
    @Override
    public ToolExecuteResult beforeExecution(ToolExecution execution) {
        WorkspaceOptions options = execution.getWorkspaceOptions();
        if (options == null || options.getAccessMode() != WorkspaceAccessMode.READ_ONLY) {
            return null;
        }
        ToolDefinition<?> tool = execution.getToolDefinition();
        if (tool == null || KernelTools.isKernel(tool.name()) || tool.readOnly()) {
            return null;
        }
        return ToolExecuteResult.err(
                "Tool '" + tool.name() + "' is not allowed in READ_ONLY workspace access mode");
    }
}
