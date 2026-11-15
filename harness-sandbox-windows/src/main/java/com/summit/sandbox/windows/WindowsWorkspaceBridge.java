package com.summit.sandbox.windows;

import com.summit.core.runtime.workspace.WorkspaceBridge;
import com.summit.core.runtime.workspace.WorkspaceCommands;
import com.summit.core.runtime.workspace.WorkspaceFiles;

import java.nio.file.Path;
import java.util.Map;

/**
 * {@link WorkspaceBridge} combining enclosed {@link WindowsWorkspaceFiles} and {@link WindowsWorkspaceCommands}.
 */
public final class WindowsWorkspaceBridge implements WorkspaceBridge {
    private final WindowsWorkspaceFiles files;
    private final WindowsWorkspaceCommands commands;

    WindowsWorkspaceBridge(Path workspaceRoot, SandboxMode mode, Map<String, String> environment) {
        this.files = new WindowsWorkspaceFiles(workspaceRoot, mode == SandboxMode.READ_WRITE);
        this.commands = new WindowsWorkspaceCommands(workspaceRoot, mode, environment);
    }

    @Override
    public WorkspaceFiles files() {
        return files;
    }

    @Override
    public WorkspaceCommands commands() {
        return commands;
    }
}
