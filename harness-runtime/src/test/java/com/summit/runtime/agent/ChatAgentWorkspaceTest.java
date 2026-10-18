package com.summit.runtime.agent;

import com.summit.core.agent.AgentRequest;
import com.summit.core.runtime.workspace.LocalWorkspaceBridge;
import com.summit.core.runtime.workspace.Workspace;
import com.summit.core.workspace.BasicWorkspaceSpec;
import com.summit.core.workspace.WorkspaceProvider;
import com.summit.core.workspace.WorkspaceResolver;
import com.summit.core.workspace.WorkspaceSpec;
import com.summit.runtime.workspace.DefaultWorkspaceResolver;
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

import static org.junit.jupiter.api.Assertions.*;

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
        assertInstanceOf(LocalWorkspaceBridge.class, workspace.bridge());
        assertSame(workspace.bridge(), workspace.bridge());
        assertEquals(workspace.id(), agent.resolveWorkspace(
                AgentRequest.builder().workspaceSpec(null).build()).id());
    }

    @Test
    void explicitLocalDirectorySupportsRealFileAccess() throws Exception {
        Files.writeString(project.resolve("example.txt"), "local workspace", StandardCharsets.UTF_8);
        Workspace workspace = agent(new LocalWorkspaceProvider()).resolveWorkspace(AgentRequest.builder()
                .workspaceSpec(new BasicWorkspaceSpec("local", project.toString())).build());

        assertEquals(project.toAbsolutePath().normalize().toString(), workspace.workDir());
        assertEquals("local workspace", workspace.bridge().files().readString(
                workspace.resolve("example.txt"), StandardCharsets.UTF_8));
    }

    @Test
    void explicitCustomSpecStillSelectsItsProviderWithoutRewritingConfiguration() {
        AtomicReference<WorkspaceSpec> received = new AtomicReference<>();
        WorkspaceProvider custom = new WorkspaceProvider() {
            public String type() { return "custom"; }
            public Workspace resolve(WorkspaceSpec spec) {
                received.set(spec);
                return new LocalWorkspace("custom-workspace", project.toString());
            }
        };
        WorkspaceSpec spec = new BasicWorkspaceSpec("custom", "/workspace", "tenant",
                Map.of("profile", "custom-profile"));

        agent(new LocalWorkspaceProvider(), custom).resolveWorkspace(
                AgentRequest.builder().workspaceSpec(spec).build());

        assertSame(spec, received.get());
    }

    private static TestAgent agent(WorkspaceProvider... providers) {
        return new TestAgent(new DefaultWorkspaceResolver(List.of(providers)));
    }

    private static final class TestAgent extends ChatAgent {
        private TestAgent(WorkspaceResolver resolver) {
            super(null, null, resolver, null, null, null);
        }

        @Override
        public String id() { return "workspace-test"; }
    }
}
