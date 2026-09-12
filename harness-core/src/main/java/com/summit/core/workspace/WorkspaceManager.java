package com.summit.core.workspace;

import com.summit.core.runtime.Workspace;

/** Application-facing workspace lifecycle facade. */
public interface WorkspaceManager {
    WorkspaceRecord create(WorkspaceSpec spec);

    WorkspaceRecord create(WorkspaceRef ref, WorkspaceSpec spec);

    /** Registers persisted or externally managed state without provisioning a resource. */
    void register(WorkspaceRecord record);

    Workspace acquire(WorkspaceRef ref);

    /** Ensures the backing resource exists and persists refreshed provider state. */
    WorkspaceRecord reconcile(WorkspaceRef ref);

    WorkspaceStatus inspect(WorkspaceRef ref);

    void destroy(WorkspaceRef ref);
}
