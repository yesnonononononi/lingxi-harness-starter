package com.summit.adapter.langchain4j.mcp;

import com.summit.core.conf.McpConfig;
import com.summit.core.runtime.workspace.Workspace;
import com.summit.core.runtime.workspace.WorkspaceStdioLaunch;
import com.summit.core.runtime.workspace.WorkspaceStdioLauncher;
import java.io.IOException;

/** Policy checks happen before discovery or process startup, never after a host-side fallback. */
final class McpWorkspaceAdapter {
    private McpWorkspaceAdapter() { }
    static boolean isolated(Workspace workspace) {
        return workspace != null && workspace.runtimeEnvironment().isolated();
    }
    static void validate(McpConfig config, Workspace workspace) {
        if (!isolated(workspace) || config.getMcp() == null || config.getMcp().isEmpty()) return;
        for (McpConfig.MCP server : config.getMcp()) {
            if (!(server.conf() instanceof McpConfig.Stdio)) {
                throw new IllegalArgumentException("Isolated workspace permits only stdio MCP: " + server.name());
            }
        }
        if (!(workspace.bridge() instanceof WorkspaceStdioLauncher)) {
            throw new IllegalStateException("Workspace does not support isolated MCP stdio startup");
        }
    }
    static WorkspaceStdioLaunch prepare(McpConfig.Stdio stdio, Workspace workspace) throws IOException {
        if (!(workspace.bridge() instanceof WorkspaceStdioLauncher launcher)) {
            throw new IllegalStateException("Workspace does not support isolated MCP stdio startup");
        }
        return launcher.prepareStdio(stdio.command(), stdio.env() == null ? java.util.Map.of() : stdio.env());
    }
}
