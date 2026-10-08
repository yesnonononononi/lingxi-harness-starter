package com.summit.runtime.agent;

import com.summit.core.agent.AgentRequest;
import com.summit.core.runtime.workspace.LocalWorkspaceBridge;
import com.summit.core.runtime.workspace.Workspace;
import com.summit.core.workspace.BasicWorkspaceSpec;
import com.summit.core.workspace.ResourceOwnership;
import com.summit.core.workspace.WorkspaceManager;
import com.summit.core.workspace.WorkspaceProvider;
import com.summit.core.workspace.WorkspaceRecord;
import com.summit.core.workspace.WorkspaceRef;
import com.summit.core.workspace.WorkspaceSpec;
import com.summit.core.workspace.WorkspaceStatus;
import com.summit.runtime.workspace.DefaultWorkspaceManager;
import com.summit.runtime.workspace.InMemoryWorkspaceStore;
import com.summit.runtime.workspace.LocalWorkspace;
import com.summit.runtime.workspace.LocalWorkspaceProvider;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertSame;

class ChatAgentWorkspaceTest {
    @TempDir
    Path project;

    @Test
    void omittedAndNullSpecsUseTheLocalProcessDirectory() {
        TestAgent agent = agent(new LocalWorkspaceProvider());

        Workspace workspace = agent.resolveWorkspace(AgentRequest.builder().build());
        assertInstanceOf(LocalWorkspace.class, workspace);
        assertEquals(Path.of(System.getProperty("user.dir")).toAbsolutePath().normalize().toString(),
                workspace.workDir());
        assertSame(LocalWorkspaceBridge.INSTANCE, workspace.bridge());
        assertEquals(workspace.id(), agent.resolveWorkspace(
                AgentRequest.builder().workspaceSpec(null).build()).id());
    }

    @Test
    void explicitLocalDirectorySupportsRealFileAccess() throws Exception {
        Files.writeString(project.resolve("example.txt"), "local workspace", StandardCharsets.UTF_8);
        Workspace workspace = agent(new LocalWorkspaceProvider()).resolveWorkspace(AgentRequest.builder()
                .workspaceSpec(new BasicWorkspaceSpec("local", project.toString())).build());

        assertEquals(project.toAbsolutePath().normalize().toString(), workspace.workDir());
        assertEquals("local workspace", workspace.bridge().readString(
                workspace.resolve("example.txt"), StandardCharsets.UTF_8));
    }

    @Test
    void explicitDockerSpecStillSelectsItsProviderWithoutRewritingConfiguration() {
        AtomicReference<WorkspaceSpec> received = new AtomicReference<>();
        WorkspaceProvider docker = new WorkspaceProvider() {
            public String type() { return "docker"; }
            public WorkspaceRecord provision(WorkspaceRef ref, WorkspaceSpec spec) {
                received.set(spec);
                return new WorkspaceRecord(ref, spec, Map.of(), ResourceOwnership.ATTACHED);
            }
            public Workspace open(WorkspaceRecord record) {
                return new LocalWorkspace(record.ref().id(), project.toString());
            }
            public WorkspaceStatus inspect(WorkspaceRecord record) { return WorkspaceStatus.ready(); }
        };
        WorkspaceSpec spec = new BasicWorkspaceSpec("docker", "/workspace", "tenant",
                Map.of("image", "custom-image"));

        agent(new LocalWorkspaceProvider(), docker).resolveWorkspace(
                AgentRequest.builder().workspaceSpec(spec).build());

        assertSame(spec, received.get());
    }

    private static TestAgent agent(WorkspaceProvider... providers) {
        return new TestAgent(new DefaultWorkspaceManager(List.of(providers), new InMemoryWorkspaceStore()));
    }

    private static final class TestAgent extends ChatAgent {
        private TestAgent(WorkspaceManager manager) {
            super(null, null, manager, null, null, null);
        }

        @Override
        public String id() { return "workspace-test"; }
    }
}
