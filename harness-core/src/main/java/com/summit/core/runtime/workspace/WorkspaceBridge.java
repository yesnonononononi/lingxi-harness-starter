package com.summit.core.runtime.workspace;

/** Groups runtime capabilities so each implementation can own files and commands independently. */
public interface WorkspaceBridge {
    WorkspaceFiles files();

    WorkspaceCommands commands();
}
