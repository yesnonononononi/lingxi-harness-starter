package com.summit.core.workspace;

import java.util.Optional;

/** Persistence extension point for provider-neutral workspace records. */
public interface WorkspaceStore {
    Optional<WorkspaceRecord> find(WorkspaceRef ref);

    void save(WorkspaceRecord record);

    void delete(WorkspaceRef ref);
}
