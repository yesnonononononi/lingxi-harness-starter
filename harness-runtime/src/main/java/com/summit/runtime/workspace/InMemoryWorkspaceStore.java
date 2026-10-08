package com.summit.runtime.workspace;

import com.summit.core.workspace.WorkspaceRecord;
import com.summit.core.workspace.WorkspaceRef;
import com.summit.core.workspace.WorkspaceStore;

import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

/** Process-local store. Scheduling and resource destruction deliberately live outside the store. */
public final class InMemoryWorkspaceStore implements WorkspaceStore {
    private final ConcurrentMap<WorkspaceRef, WorkspaceRecord> records = new ConcurrentHashMap<>();

    @Override
    public Optional<WorkspaceRecord> find(WorkspaceRef ref) {
        return Optional.ofNullable(records.get(ref));
    }

    @Override
    public void save(WorkspaceRecord record) {
        records.put(record.ref(), record);
    }

    @Override
    public void delete(WorkspaceRef ref) {
        records.remove(ref);
    }

    @Override
    public Collection<WorkspaceRecord> snapshot() {
        return List.copyOf(records.values());
    }
}
