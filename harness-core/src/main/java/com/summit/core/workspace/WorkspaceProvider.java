package com.summit.core.workspace;

import com.summit.core.runtime.Workspace;

/** Runtime backend SPI for local, Docker, Kubernetes, SSH, or other workspace kinds. */
public interface WorkspaceProvider {
    String type();

    WorkspaceRecord provision(WorkspaceRef ref, WorkspaceSpec spec);

    /** Reconciles persisted desired state with the backing resource before it is opened. */
    default WorkspaceRecord reconcile(WorkspaceRecord record) {
        return record;
    }

    Workspace open(WorkspaceRecord record);

    WorkspaceStatus inspect(WorkspaceRecord record);

    default void destroy(WorkspaceRecord record) {
        // Most providers have no backing resource to destroy. Providers that do
        // must honor ResourceOwnership before performing destructive cleanup.
    }
}
