package com.summit.core.runtime;

import com.summit.core.mcp.McpToolScope;
import com.summit.core.model.ModelInvoker;
import com.summit.core.runtime.workspace.Workspace;

public interface RuntimeFactory {
    ExecutionRuntime createRuntime(ModelInvoker invoker, Workspace workspace, McpToolScope mcpToolScope);

    /** Backwards-compatible form: a runtime without request-level MCP tools. */
    default ExecutionRuntime createRuntime(ModelInvoker invoker, Workspace workspace) {
        return createRuntime(invoker, workspace, McpToolScope.EMPTY);
    }
}
