package com.summit.runtime.workspace;

import com.summit.core.workspace.WorkspaceRef;

/** Destroys a workspace immediately or schedules a retry after a transient failure. */
@FunctionalInterface
public interface WorkspaceDestroyer {
    void destroyOrSchedule(WorkspaceRef ref);
}
