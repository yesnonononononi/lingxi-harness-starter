package com.summit.core.workspace;

import com.summit.core.runtime.workspace.Workspace;

/** Adapts a workspace specification to an execution environment. */
public interface WorkspaceProvider {
    String type();

    /**
     * Returns the environment used by a request. Resource reuse, provisioning and cleanup
     * belong to the provider; the framework neither stores resources nor destroys them.
     */
    Workspace resolve(WorkspaceSpec spec);
}
