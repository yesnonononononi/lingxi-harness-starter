package com.summit.core.workspace;

import com.summit.core.runtime.workspace.Workspace;

import java.util.List;

/** Runtime backend SPI for local, Docker, Kubernetes, SSH, or other workspace kinds. */
public interface WorkspaceProvider {
    String type();

    /**
     * Renders {@link WorkspaceSpec#scope()} for use in an identity: blank when
     * the spec carries none.
     */
    static String scopeOf(WorkspaceSpec spec) {
        String scope = spec.scope();
        return scope == null || scope.isBlank() ? "" : scope.trim();
    }

    /**
     * Natural key of the resource {@code spec} describes.
     *
     * <p>Two specs with the same natural key denote the same workspace, so the
     * framework reuses one resource instead of provisioning a second one. The
     * key always carries {@link WorkspaceSpec#scope()}, because a directory only
     * identifies a resource within one principal's isolation domain; the rest is
     * the working directory, which is the whole identity for host-mounted
     * providers such as {@code local}. Providers whose resource is addressed by
     * something else — a bind-mounted host directory, an image, a remote target
     * — must override this and keep the scope in the key.</p>
     */
    default String identityKey(WorkspaceSpec spec) {
        return type() + "|" + scopeOf(spec) + "|" + (spec.workDir() == null ? "" : spec.workDir());
    }

    WorkspaceRecord provision(WorkspaceRef ref, WorkspaceSpec spec);

    /**
     * Rebuilds the records of resources this provider still owns from an earlier run.
     *
     * <p>The records are re-derived from whatever mark the resource itself
     * carries (labels, annotations, file artifacts), never from the provider's
     * own in-memory state. A provider that cannot recognise its resources later
     * keeps the default: nothing is discovered and every workspace is simply
     * provisioned again on demand.</p>
     */
    default List<WorkspaceRecord> discoverManagedRecords() {
        return List.of();
    }

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
