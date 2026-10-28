package com.summit.core.workspace;

import com.summit.core.runtime.workspace.Workspace;

/** Selects a provider without owning the execution environment's lifetime. */
@FunctionalInterface
public interface WorkspaceResolver {
    Workspace resolve(WorkspaceSpec spec);
}
