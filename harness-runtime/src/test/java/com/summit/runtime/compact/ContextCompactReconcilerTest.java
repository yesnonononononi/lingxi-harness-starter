package com.summit.runtime.compact;

import com.summit.core.agent.AgentRequest;
import com.summit.core.conversation.message.Message;
import com.summit.core.conversation.message.UserMessageEntity;
import com.summit.core.tool.ToolExecuteResult;
import com.summit.core.tool.ToolResultType;
import com.summit.runtime.conversation.DefaultConversationManager;
import com.summit.runtime.conversation.DefaultConversationStore;
import com.summit.runtime.conversation.SystemPromptAssembler;
import com.summit.runtime.workspace.LocalWorkspace;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * One {@code compact_context} call must produce exactly one compaction: the rebuilt context may not
 * end with the request the compaction round just served, otherwise the model serves it again — a
 * second compaction, then a third (observed three rounds for a single {@code /compact} input).
 */
class ContextCompactReconcilerTest {

    @Test
    void aCompactionRoundDoesNotLeaveTheRequestItServedAtTheEndOfTheRebuiltContext() {
        DefaultConversationStore store = new DefaultConversationStore();
        DefaultConversationManager manager = manager(store);
        manager.startConversation(AgentRequest.builder()
                .sessionId("session")
                .input("/compact")
                .workspace(new LocalWorkspace("live", "."))
                .build());

        boolean compactRound = new ContextCompactReconciler(manager)
                .reconcile(List.of(compactResult()), "session");

        assertTrue(compactRound);
        List<Message> messages = store.get("session").orElseThrow().messages();
        assertFalse(messages.stream().anyMatch(message -> message instanceof UserMessageEntity user
                        && "/compact".equals(user.text())),
                "the answered /compact request must not be presented to the model again");
        assertTrue(messages.getLast() instanceof UserMessageEntity);
        assertFalse("/compact".equals(messages.getLast().text()));
    }

    private static ToolExecuteResult compactResult() {
        String output = "{\"goal\": \"g\", \"summary\": \"s\", \"completed\": [], \"pending\": [], \"state\": \"DONE\"}";
        return ToolExecuteResult.success(output, ToolResultType.CONTEXT_COMPACT);
    }

    private static DefaultConversationManager manager(DefaultConversationStore store) {
        return new DefaultConversationManager(store, null, new SystemPromptAssembler(),
                "OS=%s\nWORKDIR=%s", com.summit.core.compact.ContextAttachmentProvider.NONE, null);
    }
}
