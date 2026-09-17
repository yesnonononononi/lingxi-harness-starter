package com.summit.runtime.conversation;

import com.summit.core.agent.AgentRequest;
import com.summit.core.compact.ContextSummary;
import com.summit.core.conversation.message.Message;
import com.summit.core.conversation.message.SystemMessageEntity;
import com.summit.core.conversation.message.UserMessageEntity;
import com.summit.runtime.workspace.LocalWorkspace;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The trailing user turn of a rebuilt context decides whether a compaction happens once or forever:
 * a model-initiated compaction answered that turn, so keeping it makes the model answer it again.
 */
class DefaultConversationManagerRebuildTest {

    @Test
    void aModelInitiatedCompactionDropsTheUserTurnItJustAnswered() {
        DefaultConversationStore store = new DefaultConversationStore();
        DefaultConversationManager manager = manager(store);
        manager.startConversation(request("/compact"));

        manager.rebuildContext(summary(), "session", true);

        List<Message> messages = store.get("session").orElseThrow().messages();
        assertFalse(containsUserText(messages, "/compact"),
                "the request the compaction round served must not survive the rebuild");
        UserMessageEntity last = assertInstanceOf(UserMessageEntity.class, messages.getLast());
        assertTrue(last.text().contains("compacted"), "the rebuilt context ends with the continue instruction");
    }

    @Test
    void aCheckpointCompactionKeepsTheUserTurnTheModelNeverSaw() {
        DefaultConversationStore store = new DefaultConversationStore();
        DefaultConversationManager manager = manager(store);
        manager.startConversation(request("实现模型 CRUD"));

        manager.rebuildContext(summary(), "session");

        List<Message> messages = store.get("session").orElseThrow().messages();
        assertTrue(containsUserText(messages, "实现模型 CRUD"),
                "a request the model has not answered yet must survive the rebuild");
    }

    @Test
    void aCheckpointCompactionCanAlsoBeToldThatTheTurnIsAnswered() {
        DefaultConversationStore store = new DefaultConversationStore();
        DefaultConversationManager manager = manager(store);
        manager.startConversation(request("实现模型 CRUD"));

        manager.rebuildContext(summary(), "session", true);

        assertFalse(containsUserText(store.get("session").orElseThrow().messages(), "实现模型 CRUD"));
    }

    private static AgentRequest request(String input) {
        return AgentRequest.builder()
                .sessionId("session")
                .input(input)
                .workspace(new LocalWorkspace("live", "."))
                .build();
    }

    private static boolean containsUserText(List<Message> messages, String text) {
        return messages.stream().anyMatch(message -> message instanceof UserMessageEntity user
                && text.equals(user.text()));
    }

    private static ContextSummary summary() {
        return ContextSummary.builder()
                .goal("goal")
                .summary("summary")
                .completed(List.of())
                .pending(List.of())
                .state("DONE")
                .build();
    }

    private static DefaultConversationManager manager(DefaultConversationStore store) {
        return new DefaultConversationManager(store, null, new SystemPromptAssembler(),
                "OS=%s\nWORKDIR=%s", com.summit.core.compact.ContextAttachmentProvider.NONE, null);
    }
}
