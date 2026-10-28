package com.summit.core.runtime.workspace;

import com.summit.core.runtime.RuntimeEnvironment;

public final class LocalWorkspaceBridge implements WorkspaceBridge {
    private final WorkspaceFiles files;
    private final WorkspaceCommands commands;

    public LocalWorkspaceBridge(ShellType shellType) {
        this(RuntimeEnvironment.builder().shellType(shellType).build());
    }

    public LocalWorkspaceBridge(RuntimeEnvironment environment) {
        this.files = new LocalWorkspaceFiles();
        this.commands = new LocalWorkspaceCommands(environment);
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
