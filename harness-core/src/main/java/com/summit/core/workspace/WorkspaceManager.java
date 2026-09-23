package com.summit.core.workspace;

import com.summit.core.runtime.workspace.Workspace;

/** Application-facing workspace lifecycle facade. */
public interface WorkspaceManager {
    /** Provisions the workspace {@code spec} describes. Repeat calls for the same spec return the same record. */
    WorkspaceRecord create(WorkspaceSpec spec);

    /**
     * Returns the workspace {@code spec} describes, provisioning it on first use.
     *
     * <p>This is the entry point callers use when they describe <em>what</em>
     * they need instead of naming an existing workspace: the identity is derived
     * from the spec, so the same configuration always lands on the same
     * resource. A workspace resolved here outlives the request — destruction is
     * explicit through {@link #destroy(WorkspaceRef)}.</p>
     */
    WorkspaceRecord resolve(WorkspaceSpec spec);

    /** {@link #resolve(WorkspaceSpec)} followed by {@link #acquire(WorkspaceRef)}. */
    default Workspace acquire(WorkspaceSpec spec) {
        return acquire(resolve(spec).ref());
    }

    /** Registers persisted or externally managed state without provisioning a resource. */
    void register(WorkspaceRecord record);

    /**
     * Adopts the resources every installed provider still owns from an earlier run.
     *
     * <p>Identical in effect to calling {@link #create(WorkspaceSpec)} for each
     * resource, except that nothing is provisioned: a discovered record is only
     * registered, so a resource that is already known is left untouched.</p>
     *
     * @return how many records were adopted; providers that cannot recognise
     *         their own resources contribute none
     */
    default int restoreManagedRecords() {
        return 0;
    }

    Workspace acquire(WorkspaceRef ref);

    /** Ensures the backing resource exists and persists refreshed provider state. */
    WorkspaceRecord reconcile(WorkspaceRef ref);

    WorkspaceStatus inspect(WorkspaceRef ref);

    void destroy(WorkspaceRef ref);
}
