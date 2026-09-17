package com.summit.runtime.workspace;

import com.summit.core.workspace.BasicWorkspaceSpec;
import com.summit.core.workspace.WorkspaceManager;
import com.summit.core.workspace.WorkspaceRecord;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.BeforeEach;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

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
    void storeSnapshotIsStable() {
        InMemoryWorkspaceStore store = new InMemoryWorkspaceStore();
        WorkspaceManager manager = new DefaultWorkspaceManager(
                List.of(new LocalWorkspaceProvider()), store);
        WorkspaceRecord created = manager.create(new BasicWorkspaceSpec("local", tempDir.toString()));

        var snapshot = store.snapshot();
        manager.destroy(created.ref());

        assertEquals(1, snapshot.size());
        assertTrue(store.snapshot().isEmpty());
    }

}
