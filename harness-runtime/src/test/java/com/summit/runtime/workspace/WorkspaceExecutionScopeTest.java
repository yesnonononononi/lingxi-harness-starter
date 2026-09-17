package com.summit.runtime.workspace;

import com.summit.core.runtime.workspace.Workspace;
import com.summit.core.workspace.BasicWorkspaceSpec;
import com.summit.core.workspace.ResourceOwnership;
import com.summit.core.workspace.WorkspaceProvider;
import com.summit.core.workspace.WorkspaceRecord;
import com.summit.core.workspace.WorkspaceRef;
import com.summit.core.workspace.WorkspaceSpec;
import com.summit.core.workspace.WorkspaceStatus;
import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

class WorkspaceExecutionScopeTest {

    @Test
    void destroysTheManagedWorkspaceWhenExecutionEnds() {
        RecordingProvider provider = new RecordingProvider(false);
        var manager = new DefaultWorkspaceManager(List.of(provider), new InMemoryWorkspaceStore());

        WorkspaceRef ref;
        try (WorkspaceExecutionScope scope = WorkspaceExecutionScope.open(
                manager, new BasicWorkspaceSpec("recording", Path.of(".").toAbsolutePath().toString()))) {
            ref = scope.record().ref();
            assertNotNull(scope.workspace());
        }

        assertEquals(1, provider.destroyCount.get());
        assertThrows(IllegalArgumentException.class, () -> manager.inspect(ref));
    }

    @Test
    void cleansUpWhenAcquireFails() {
        RecordingProvider provider = new RecordingProvider(true);
        var manager = new DefaultWorkspaceManager(List.of(provider), new InMemoryWorkspaceStore());

        assertThrows(IllegalStateException.class, () -> WorkspaceExecutionScope.open(
                manager, new BasicWorkspaceSpec("recording", Path.of(".").toAbsolutePath().toString())));

        assertEquals(1, provider.destroyCount.get());
    }

    @Test
    void closeIsIdempotent() {
        RecordingProvider provider = new RecordingProvider(false);
        var manager = new DefaultWorkspaceManager(List.of(provider), new InMemoryWorkspaceStore());
        WorkspaceExecutionScope scope = WorkspaceExecutionScope.open(
                manager, new BasicWorkspaceSpec("recording", Path.of(".").toAbsolutePath().toString()));

        scope.close();
        scope.close();

        assertEquals(1, provider.destroyCount.get());
    }

    private static final class RecordingProvider implements WorkspaceProvider {
        private final boolean failOnOpen;
        private final AtomicInteger destroyCount = new AtomicInteger();

        private RecordingProvider(boolean failOnOpen) {
            this.failOnOpen = failOnOpen;
        }

        @Override
        public String type() {
            return "recording";
        }

        @Override
        public WorkspaceRecord provision(WorkspaceRef ref, WorkspaceSpec spec) {
            return new WorkspaceRecord(ref, spec, Map.of(), ResourceOwnership.MANAGED);
        }

        @Override
        public Workspace open(WorkspaceRecord record) {
            if (failOnOpen) {
                throw new IllegalStateException("open failed");
            }
            return new LocalWorkspace(record.ref().id(), record.spec().workDir());
        }

        @Override
        public WorkspaceStatus inspect(WorkspaceRecord record) {
            return WorkspaceStatus.ready();
        }

        @Override
        public void destroy(WorkspaceRecord record) {
            destroyCount.incrementAndGet();
        }
    }
}
