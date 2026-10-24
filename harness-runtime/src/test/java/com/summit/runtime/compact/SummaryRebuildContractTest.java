package com.summit.runtime.compact;

import com.summit.core.agent.AgentRequest;
import com.summit.core.agent.Execution;
import com.summit.core.compact.ContextAttachmentProvider;
import com.summit.core.compact.ContextCompactRequest;
import com.summit.core.conversation.api.ChatRequestEntity;
import com.summit.core.conversation.api.ChatResponseEntity;
import com.summit.core.conversation.event.RuntimeEventPublisher;
import com.summit.core.conversation.message.AiMessageEntity;
import com.summit.core.conversation.message.Message;
import com.summit.core.conversation.message.SystemMessageEntity;
import com.summit.core.conversation.message.UserMessageEntity;
import com.summit.core.runtime.loop.ContextUsageReporter;
import com.summit.runtime.conversation.DefaultConversationManager;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The two non-deletable contracts of a summary rebuild (design §3.4) — cases 10 to 12 of §8.2.
 *
 * <p>Premise B: a rebuild must keep <b>every</b> user message of the pre-rebuild context, not just
 * the retainable tail. Premise A: the summary a rebuild writes must be the input of the next
 * summarize round, so replacing the history never loses what was already compressed.</p>
 */
class SummaryRebuildContractTest {

    private static final double OVER_THRESHOLD = 0.95;
    private static final String SUMMARY_JSON = "{\"summary\":\"SUMMARY-PREVIOUS\",\"goal\":\"goal\"}";

    /** Case 10 (P0-2 regression): every user message survives the rebuild, in original order. */
    @Test
    void summaryRebuildKeepsAllUserMessages() {
        DefaultConversationManager conversations = new DefaultConversationManager(ContextAttachmentProvider.NONE);
        // The trailing assistant messages carry no thinking, so retainableTail() keeps nothing:
        // without premise B every user message but the newest one disappears here.
        Execution execution = execution(
                SystemMessageEntity.builder().text("LEADING").build(),
                UserMessageEntity.from("user-one"),
                AiMessageEntity.builder().text("answer-one").build(),
                UserMessageEntity.from("user-two"),
                AiMessageEntity.builder().text("answer-two").build(),
                UserMessageEntity.from("user-three"));

        conversations.rebuildContext("compressed summary", execution, false);

        assertEquals(List.of("user-one", "user-two", "user-three"), userTexts(execution),
                "a summary rebuild must keep every user message of the compressed context");
    }

    /** Case 11 (premise A): the second summarize round sees the previous summary. */
    @Test
    void summaryInputIncludesPreviousSummarySystemMessage() {
        List<ChatRequestEntity> requests = new ArrayList<>();
        DefaultConversationManager conversations = new DefaultConversationManager(ContextAttachmentProvider.NONE);
        StubContextTokenizer tokenizer = new StubContextTokenizer(OVER_THRESHOLD);
        ContextUsageReporter usage = new ContextUsageReporter(tokenizer, 100_000,
                new RuntimeEventPublisher(List.of()), 1);
        DefaultModelCompacter compacter = new DefaultModelCompacter(request -> {
            requests.add(request);
            return ChatResponseEntity.builder()
                    .aiMessageEntity(AiMessageEntity.builder().text(SUMMARY_JSON).build())
                    .build();
        }, conversations, id -> Optional.empty(), usage);
        Execution execution = execution(
                SystemMessageEntity.builder().text("LEADING-SYSTEM-PROMPT").build(),
                UserMessageEntity.from("history the first round compresses"));

        assertTrue(compacter.compact(new ContextCompactRequest(execution, null)));
        assertTrue(compacter.compact(new ContextCompactRequest(execution, null)));

        assertEquals(2, requests.size());
        String secondInput = requests.get(1).getMessages().getLast().text();
        assertTrue(secondInput.contains("SUMMARY-PREVIOUS"),
                "the previous summary must be part of the next summarize input: " + secondInput);
        assertFalse(secondInput.contains("LEADING-SYSTEM-PROMPT"),
                "the leading business prompt stays out of the compact-model input: " + secondInput);
    }

    /** Case 12: the leading system message and the protected attachment survive the rebuild. */
    @Test
    void summaryRebuildKeepsLeadingSystemAndAttachment() {
        DefaultConversationManager conversations =
                new DefaultConversationManager(executionId -> Optional.of("ATTACHMENT-STATE-42"));
        Execution execution = execution(
                SystemMessageEntity.builder().text("LEADING").build(),
                UserMessageEntity.from("user-one"));

        conversations.rebuildContext("compressed summary", execution, false);

        List<Message> rebuilt = execution.getMessages();
        assertEquals("LEADING", rebuilt.getFirst().text(),
                "the leading system message must stay first");
        List<String> systems = rebuilt.stream()
                .filter(SystemMessageEntity.class::isInstance)
                .map(Message::text)
                .toList();
        assertTrue(systems.stream().anyMatch(text -> text.contains("ATTACHMENT-STATE-42")),
                "the protected attachment must be re-emitted: " + systems);
        assertTrue(systems.stream().anyMatch(text -> text.contains("compressed summary")),
                "the new summary must be present: " + systems);
        assertEquals(List.of("user-one"), userTexts(execution), "the user message survives the rebuild");

        // Systems-first order: leading, attachment and summary all precede every non-system message.
        int lastSystemIndex = -1;
        int firstNonSystemIndex = Integer.MAX_VALUE;
        for (int i = 0; i < rebuilt.size(); i++) {
            if (rebuilt.get(i) instanceof SystemMessageEntity) {
                lastSystemIndex = Math.max(lastSystemIndex, i);
            } else {
                firstNonSystemIndex = Math.min(firstNonSystemIndex, i);
            }
        }
        assertTrue(lastSystemIndex < firstNonSystemIndex,
                "the rebuilt context must put every system message before the rest");
    }

    /**
     * Case 10b: premise B survives the answered-trailing-turn path.
     *
     * <p>The {@code compact_context} tool rebuild passes {@code answeredTrailingUserTurn=true}, for
     * which {@code retainableTail()} returns nothing. The old behaviour then dropped <b>every</b>
     * user message and injected a bare "continue" prompt, losing what the user actually asked for.
     * Premise B (design §3.4) overrides that: all user messages must still survive the rebuild.</p>
     */
    @Test
    void summaryRebuildKeepsAllUserMessagesEvenWhenTrailingTurnAnswered() {
        DefaultConversationManager conversations = new DefaultConversationManager(ContextAttachmentProvider.NONE);
        Execution execution = execution(
                SystemMessageEntity.builder().text("LEADING").build(),
                UserMessageEntity.from("user-one"),
                AiMessageEntity.builder().text("answer-one").build(),
                UserMessageEntity.from("user-two"));

        conversations.rebuildContext("compressed summary", execution, true);

        assertEquals(List.of("user-one", "user-two"), userTexts(execution),
                "an answered trailing turn must not drop the earlier user messages");
        boolean injectedContinuePrompt = execution.getMessages().stream()
                .filter(UserMessageEntity.class::isInstance)
                .map(Message::text)
                .anyMatch(text -> text.contains("compacted into the summary above"));
        assertFalse(injectedContinuePrompt,
                "user messages exist, so the bare continue prompt must not be injected");
    }

    // ------------------------------------------------------------------ fixtures

    /** A session built from the given messages, with the first system text as its business prompt. */
    private static Execution execution(Message... messages) {
        List<Message> copy = new ArrayList<>(List.of(messages));
        String systemPrompt = copy.stream()
                .filter(SystemMessageEntity.class::isInstance)
                .map(Message::text)
                .findFirst()
                .orElse("");
        return Execution.builder().id("execution-1")
                .agentRequest(AgentRequest.builder().messages(copy).systemPrompt(systemPrompt).build())
                .messages(copy).build();
    }

    /** The texts of the user messages still present in the session, in current order. */
    private static List<String> userTexts(Execution execution) {
        return execution.getMessages().stream()
                .filter(UserMessageEntity.class::isInstance)
                .map(Message::text)
                .toList();
    }
}
