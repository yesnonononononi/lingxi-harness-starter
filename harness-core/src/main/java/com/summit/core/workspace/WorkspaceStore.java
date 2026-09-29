package com.summit.core.workspace;

import java.util.Collection;
import java.util.List;
import java.util.Optional;

/** Persistence extension point for provider-neutral workspace records. */
public interface WorkspaceStore {
    Optional<WorkspaceRecord> find(WorkspaceRef ref);
    void save(WorkspaceRecord record);
    void delete(WorkspaceRef ref);

    /** Returns a stable point-in-time view for diagnostics and reconciliation. */
    default Collection<WorkspaceRecord> snapshot() {
        return List.of();
    }
}
