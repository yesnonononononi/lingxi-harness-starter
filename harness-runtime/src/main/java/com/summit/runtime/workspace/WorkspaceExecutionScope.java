package com.summit.runtime.workspace;

import com.summit.core.runtime.workspace.Workspace;
import com.summit.core.workspace.WorkspaceManager;
import com.summit.core.workspace.WorkspaceRecord;
import com.summit.core.workspace.WorkspaceSpec;

import java.util.Objects;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Owns the smallest complete workspace lifecycle: create, acquire and destroy.
 * A scope only manages a workspace it created itself.
 */
public final class WorkspaceExecutionScope implements AutoCloseable {
    private final WorkspaceManager manager;
    private final WorkspaceRecord record;
    private final Workspace workspace;
    private final WorkspaceDestroyer destroyer;
    private final AtomicBoolean closed = new AtomicBoolean();

    private WorkspaceExecutionScope(WorkspaceManager manager,
                                    WorkspaceRecord record,
                                    Workspace workspace, WorkspaceDestroyer destroyer) {
        this.manager = manager;
        this.record = record;
        this.workspace = workspace;
        this.destroyer = destroyer;
    }

    public static WorkspaceExecutionScope open(WorkspaceManager manager, WorkspaceSpec spec) {
        return open(manager, spec, manager::destroy);
    }

    public static WorkspaceExecutionScope open(WorkspaceManager manager, WorkspaceSpec spec,
                                               WorkspaceDestroyer destroyer) {
        Objects.requireNonNull(manager, "workspace manager");
        Objects.requireNonNull(spec, "workspace spec");
        Objects.requireNonNull(destroyer, "workspace destroyer");

        WorkspaceRecord record = manager.create(spec);
        try {
            return new WorkspaceExecutionScope(manager, record, manager.acquire(record.ref()), destroyer);
        } catch (RuntimeException | Error acquireFailure) {
            try {
                destroyer.destroyOrSchedule(record.ref());
            } catch (RuntimeException | Error cleanupFailure) {
                acquireFailure.addSuppressed(cleanupFailure);
            }
            throw acquireFailure;
        }
    }

    public Workspace workspace() {
        if (closed.get()) {
            throw new IllegalStateException("workspace execution scope is closed");
        }
        return workspace;
    }

    public WorkspaceRecord record() {
        return record;
    }

    @Override
    public void close() {
        if (closed.compareAndSet(false, true)) {
            destroyer.destroyOrSchedule(record.ref());
        }
    }
}
