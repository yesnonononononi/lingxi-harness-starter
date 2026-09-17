package com.summit.runtime.workspace;

import com.summit.core.runtime.workspace.Workspace;
import com.summit.core.workspace.WorkspaceManager;
import com.summit.core.workspace.WorkspaceRecord;
import com.summit.core.workspace.WorkspaceRef;
import com.summit.core.workspace.WorkspaceSpec;
import com.summit.core.workspace.WorkspaceStatus;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;

class WorkspaceDestroyReaperTest {

    @Test
    void retriesAnExplicitlyFailedDestroy() throws InterruptedException {
        FailingOnceManager manager = new FailingOnceManager();
        try (WorkspaceDestroyReaper reaper = new WorkspaceDestroyReaper(
                manager, Duration.ofHours(1), Duration.ofMillis(1), 3)) {
            reaper.destroyOrSchedule(new WorkspaceRef("temporary"));
            assertEquals(1, reaper.pendingCount());

            Thread.sleep(5);
            reaper.reapNow();

            assertEquals(2, manager.destroyAttempts.get());
            assertEquals(0, reaper.pendingCount());
        }
    }

    private static final class FailingOnceManager implements WorkspaceManager {
        private final AtomicInteger destroyAttempts = new AtomicInteger();

        @Override public WorkspaceRecord create(WorkspaceSpec spec) { throw new UnsupportedOperationException(); }
        @Override public void register(WorkspaceRecord record) { throw new UnsupportedOperationException(); }
        @Override public Workspace acquire(WorkspaceRef ref) { throw new UnsupportedOperationException(); }
        @Override public WorkspaceRecord reconcile(WorkspaceRef ref) { throw new UnsupportedOperationException(); }
        @Override public WorkspaceStatus inspect(WorkspaceRef ref) { throw new UnsupportedOperationException(); }

        @Override
        public void destroy(WorkspaceRef ref) {
            if (destroyAttempts.incrementAndGet() == 1) {
                throw new IllegalStateException("Docker is temporarily unavailable");
            }
        }
    }
}
