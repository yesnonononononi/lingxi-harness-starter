package com.summit.runtime.conversation;

import com.summit.core.agent.AgentRequest;
import com.summit.core.conversation.ConversationEntity;
import com.summit.core.conversation.message.SystemMessageEntity;
import com.summit.core.conversation.message.UserMessageEntity;
import com.summit.core.runtime.workspace.Workspace;
import com.summit.core.tool.LoopBoundary;
import com.summit.runtime.workspace.LocalWorkspace;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertTrue;

class DefaultConversationManagerWorkspaceTest {

    @Test
    void refreshBoundaryUsesLiveWorkspaceWhenConversationHasNoWorkspaceSpec() {
        DefaultConversationStore store = new DefaultConversationStore();
        DefaultConversationManager manager = new DefaultConversationManager(
                store, null, new SystemPromptAssembler(), "OS=%s\nWORKDIR=%s", null, null);
        Workspace workspace = new LocalWorkspace("live", ".");
        AgentRequest request = AgentRequest.builder()
                .sessionId("session")
                .input("build it")
                .workspace(workspace)
                .build();

        manager.startConversation(request);

        assertDoesNotThrow(() -> manager.refreshBoundary(
                "session", LoopBoundary.EXECUTE, null, null, null, workspace));
        ConversationEntity conversation = store.get("session").orElseThrow();
        String prompt = ((SystemMessageEntity) conversation.messages().getFirst()).text();
        assertTrue(prompt.contains("WORKDIR=" + workspace.workDir()));
        assertTrue(prompt.contains("边界: EXECUTE"));
    }

    @Test
    void internalDirectiveIsMarkedAsNotUserAuthored() {
        DefaultConversationStore store = new DefaultConversationStore();
        DefaultConversationManager manager = new DefaultConversationManager(
                store, null, new SystemPromptAssembler(), "OS=%s\nWORKDIR=%s", null, null);
        Workspace workspace = new LocalWorkspace("live", ".");
        manager.startConversation(AgentRequest.builder()
                .sessionId("session")
                .input("build it")
                .workspace(workspace)
                .build());

        manager.appendInternalUserMessage("session", "approved plan directive");

        UserMessageEntity directive = (UserMessageEntity) store.get("session").orElseThrow()
                .messages().getLast();
        assertTrue(directive.isInternal());
    }
}
