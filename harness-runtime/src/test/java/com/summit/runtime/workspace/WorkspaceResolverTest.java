package com.summit.runtime.workspace;

import com.summit.core.runtime.RuntimeEnvironment;
import com.summit.core.runtime.workspace.Workspace;
import com.summit.core.workspace.BasicWorkspaceSpec;
import com.summit.core.workspace.WorkspaceProvider;
import com.summit.core.workspace.WorkspaceSpec;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;

class WorkspaceResolverTest {
    @TempDir
    Path project;

    @Test
    void repeatedResolutionDelegatesToTheProviderWithoutCachingOrClosingItsWorkspace() {
        AtomicInteger calls = new AtomicInteger();
        AtomicReference<WorkspaceSpec> received = new AtomicReference<>();
        CloseTrackingWorkspace shared = new CloseTrackingWorkspace();
        WorkspaceProvider provider = new WorkspaceProvider() {
            public String type() { return "custom"; }
            public Workspace resolve(WorkspaceSpec spec) {
                calls.incrementAndGet();
                received.set(spec);
                return shared;
            }
        };
        DefaultWorkspaceResolver resolver = new DefaultWorkspaceResolver(List.of(provider));
        WorkspaceSpec spec = new BasicWorkspaceSpec(" CUSTOM ", project.toString(), "principal",
                Map.of("container", "externally-managed"));

        assertSame(shared, resolver.resolve(spec));
        assertSame(shared, resolver.resolve(spec));
        assertSame(spec, received.get());
        assertEquals(2, calls.get());
        assertEquals(0, shared.closes);
    }

    @Test
    void providerFailurePropagatesWithoutFallingBackToTheHost() {
        IllegalStateException unavailable = new IllegalStateException("custom sandbox unavailable");
        WorkspaceProvider provider = new WorkspaceProvider() {
            public String type() { return "custom"; }
            public Workspace resolve(WorkspaceSpec spec) { throw unavailable; }
        };
        DefaultWorkspaceResolver resolver = new DefaultWorkspaceResolver(
                List.of(new LocalWorkspaceProvider(), provider));

        assertSame(unavailable, assertThrows(IllegalStateException.class,
                () -> resolver.resolve(new BasicWorkspaceSpec("custom", project.toString()))));
        assertThrows(IllegalArgumentException.class,
                () -> resolver.resolve(new BasicWorkspaceSpec("missing", project.toString())));
    }

    @Test
    void duplicateProviderTypesAreRejectedAfterNormalization() {
        WorkspaceProvider duplicate = new WorkspaceProvider() {
            public String type() { return " LOCAL "; }
            public Workspace resolve(WorkspaceSpec spec) { throw new AssertionError("unused"); }
        };
        assertThrows(IllegalArgumentException.class,
                () -> new DefaultWorkspaceResolver(List.of(new LocalWorkspaceProvider(), duplicate)));
    }

    @Test
    void localResolutionRechecksTheDirectoryOnEveryCall() throws Exception {
        Path directory = Files.createDirectory(project.resolve("workspace"));
        DefaultWorkspaceResolver resolver = new DefaultWorkspaceResolver(List.of(new LocalWorkspaceProvider()));
        WorkspaceSpec spec = new BasicWorkspaceSpec("local", directory.toString());

        resolver.resolve(spec);
        Files.delete(directory);

        assertThrows(IllegalArgumentException.class, () -> resolver.resolve(spec));
    }

    @Test
    void normalizedLocalPathsKeepIdentityWhileDifferentScopesRemainDistinct() {
        LocalWorkspaceProvider provider = new LocalWorkspaceProvider();
        Workspace first = provider.resolve(new BasicWorkspaceSpec("local", project.toString(), "one", Map.of()));
        Workspace equivalent = provider.resolve(new BasicWorkspaceSpec("local",
                project.resolve("unused").resolve("..").toString(), "one", Map.of()));
        Workspace otherScope = provider.resolve(
                new BasicWorkspaceSpec("local", project.toString(), "two", Map.of()));

        assertEquals(first.id(), equivalent.id());
        assertNotEquals(first.id(), otherScope.id());
        assertEquals(project.toAbsolutePath().normalize().toString(), first.workDir());
    }

    private static final class CloseTrackingWorkspace implements Workspace, AutoCloseable {
        private final LocalWorkspace delegate = new LocalWorkspace("shared-container", System.getProperty("user.dir"));
        private int closes;

        public String id() { return "shared-container"; }
        public RuntimeEnvironment runtimeEnvironment() { return delegate.runtimeEnvironment(); }
        public String workDir() { return delegate.workDir(); }
        public Path resolve(String path) { return delegate.resolve(path); }
        public void close() { closes++; }
    }
}
