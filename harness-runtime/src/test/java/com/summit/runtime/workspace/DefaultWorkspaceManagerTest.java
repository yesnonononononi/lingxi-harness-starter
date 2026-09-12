package com.summit.runtime.workspace;

import com.summit.core.runtime.Workspace;
import com.summit.core.workspace.BasicWorkspaceSpec;
import com.summit.core.workspace.ResourceOwnership;
import com.summit.core.workspace.WorkspaceManager;
import com.summit.core.workspace.WorkspaceProvider;
import com.summit.core.workspace.WorkspaceRecord;
import com.summit.core.workspace.WorkspaceRef;
import com.summit.core.workspace.WorkspaceSpec;
import com.summit.core.workspace.WorkspaceStatus;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.BeforeEach;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class DefaultWorkspaceManagerTest {
    private final Path tempDir = Path.of("target", "workspace-tests").toAbsolutePath().normalize();

    @BeforeEach
    void createWorkspaceDirectory() throws IOException {
        Files.createDirectories(tempDir);
    }

    @Test
    void createsPersistsAndAcquiresLocalWorkspace() {
        WorkspaceManager manager = new DefaultWorkspaceManager(
                List.of(new LocalWorkspaceProvider()), new InMemoryWorkspaceStore());
        WorkspaceRef ref = new WorkspaceRef("project-1");

        WorkspaceRecord record = manager.create(ref,
                new BasicWorkspaceSpec("local", tempDir.toString()));
        Workspace workspace = manager.acquire(record.ref());

        assertEquals(ref.id(), workspace.id());
        assertEquals(tempDir.toAbsolutePath().normalize().toString(), workspace.workDir());
        assertEquals(tempDir.resolve("src").normalize(), workspace.resolve("src"));
        assertTrue(manager.inspect(ref).state()
                == com.summit.core.workspace.WorkspaceStatus.State.READY);
    }

    @Test
    void confinesRelativePathsToWorkspaceRoot() {
        LocalWorkspace workspace = new LocalWorkspace("project-1", tempDir.toString());
        assertThrows(IllegalArgumentException.class, () -> workspace.resolve("../outside.txt"));
    }

    @Test
    void reportsMissingProviderAtRegistrationBoundary() {
        WorkspaceManager manager = new DefaultWorkspaceManager(
                List.of(new LocalWorkspaceProvider()), new InMemoryWorkspaceStore());
        assertThrows(IllegalArgumentException.class, () -> manager.create(
                new BasicWorkspaceSpec("not-installed", tempDir.toString())));
    }

    @Test
    void acquireReconcilesAndPersistsProviderState() {
        InMemoryWorkspaceStore store = new InMemoryWorkspaceStore();
        WorkspaceProvider provider = new WorkspaceProvider() {
            @Override public String type() { return "recoverable"; }
            @Override public WorkspaceRecord provision(WorkspaceRef ref, WorkspaceSpec spec) {
                return new WorkspaceRecord(ref, spec, Map.of(), ResourceOwnership.MANAGED);
            }
            @Override public WorkspaceRecord reconcile(WorkspaceRecord record) {
                return record.withProviderState(Map.of("resourceId", "restored"));
            }
            @Override public Workspace open(WorkspaceRecord record) {
                return new LocalWorkspace(record.ref().id(), record.spec().workDir());
            }
            @Override public WorkspaceStatus inspect(WorkspaceRecord record) {
                return WorkspaceStatus.ready();
            }
        };
        WorkspaceManager manager = new DefaultWorkspaceManager(List.of(provider), store);
        WorkspaceRef ref = new WorkspaceRef("recoverable-project");
        manager.create(ref, new BasicWorkspaceSpec("recoverable", tempDir.toString()));

        manager.acquire(ref);

        assertEquals("restored", store.find(ref).orElseThrow().providerState().get("resourceId"));
    }
}
