package com.summit.runtime.conversation;

import com.summit.core.agent.AgentRequest;
import com.summit.core.conversation.ConversationEntity;
import com.summit.core.conversation.event.RuntimeEventPublisher;
import com.summit.core.runtime.Workspace;
import com.summit.core.workspace.BasicWorkspaceSpec;
import com.summit.core.workspace.WorkspaceManager;
import com.summit.core.workspace.WorkspaceRef;
import com.summit.runtime.coreTools.plan.DefaultPlanStore;
import com.summit.runtime.workspace.DefaultWorkspaceManager;
import com.summit.runtime.workspace.InMemoryWorkspaceStore;
import com.summit.runtime.workspace.LocalWorkspaceProvider;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;

class WorkspaceReferenceConversationTest {
    private final Path root = Path.of("target", "conversation-workspace-test")
            .toAbsolutePath().normalize();

    @BeforeEach
    void createRoot() throws IOException {
        Files.createDirectories(root);
    }

    @Test
    void persistsReferenceAndResolvesLiveWorkspaceOnDemand() {
        WorkspaceManager workspaceManager = new DefaultWorkspaceManager(
                List.of(new LocalWorkspaceProvider()), new InMemoryWorkspaceStore());
        WorkspaceRef ref = new WorkspaceRef("conversation-project");
        workspaceManager.create(ref, new BasicWorkspaceSpec("local", root.toString()));

        DefaultConversationStore store = new DefaultConversationStore();
        DefaultConversationManager conversations = new DefaultConversationManager(
                store, new RuntimeEventPublisher(List.of()), new SystemPromptAssembler(),
                "OS: %s%nWorkspace: %s", new DefaultPlanStore(), workspaceManager);
        Workspace live = workspaceManager.acquire(ref);

        conversations.startConversation(AgentRequest.builder()
                .sessionId("session-1")
                .input("hello")
                .workspace(live)
                .workspaceRef(ref)
                .build());

        ConversationEntity persisted = store.get("session-1").orElseThrow();
        assertEquals(ref, persisted.workspaceRef());
        assertNull(persisted.workspace());
        assertNotNull(conversations.workspace("session-1"));
        assertEquals(ref.id(), conversations.workspace("session-1").id());
    }
}
