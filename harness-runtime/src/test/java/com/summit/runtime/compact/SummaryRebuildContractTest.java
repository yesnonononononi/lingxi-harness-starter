package com.summit.runtime.compact;

import com.summit.core.agent.AgentRequest;
import com.summit.core.agent.ContextSpec;
import com.summit.core.agent.Execution;
import com.summit.core.compact.ContextAttachmentProvider;
import com.summit.core.compact.ContextCompactRequest;
import com.summit.core.conversation.api.ChatRequestEntity;
import com.summit.core.conversation.api.ChatResponseEntity;
import com.summit.core.conversation.event.RuntimeEventPublisher;
import com.summit.core.conversation.api.ToolCallRequest;
import com.summit.core.conversation.message.AiMessageEntity;
import com.summit.core.conversation.message.Message;
import com.summit.core.conversation.message.SystemMessageEntity;
import com.summit.core.conversation.message.ToolMessageEntity;
import com.summit.core.conversation.message.UserMessageEntity;
import com.summit.core.runtime.loop.ContextUsageReporter;
import com.summit.runtime.conversation.DefaultConversationManager;
import com.summit.runtime.conversation.DefaultTokenizer;
import com.summit.runtime.prompt.SystemPromptAssembler;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import com.summit.core.conversation.event.ContextEventPublisher;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import com.summit.core.conversation.CheckpointSaver;

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

    @Test
    void consecutiveRebuildsPreservePendingInputUntilALaterReply() {
        DefaultConversationManager conversations = DefaultConversationManager.builder().contextAttachmentProvider(ContextAttachmentProvider.NONE).build();
        AiMessageEntity earlierReply = AiMessageEntity.builder().text("earlier reply").build();
        UserMessageEntity notification = UserMessageEntity.from("incoming mail").markPendingCompaction();
        Execution execution = execution(SystemMessageEntity.builder().text("system").build(),
                UserMessageEntity.from("question"), earlierReply, notification);

        conversations.rebuildContext("first summary", execution, CheckpointSaver.NONE);
        conversations.rebuildContext("second summary before delivery", execution, CheckpointSaver.NONE);

        assertEquals(List.of("question", "incoming mail"), userTexts(execution));
        assertEquals(List.of(earlierReply, notification), execution.getMessages().subList(3, 5));
        assertEquals(false, notification.getAttrs().get(UserMessageEntity.COMPACT));

        execution.getMessages().add(AiMessageEntity.builder().text("mail received").build());
        conversations.rebuildContext("summary after delivery", execution, CheckpointSaver.NONE);

        UserMessageEntity delivered = execution.getMessages().stream().filter(message -> message.text().equals("incoming mail"))
                .map(UserMessageEntity.class::cast).findFirst().orElseThrow();
        assertEquals(true, delivered.getAttrs().get(UserMessageEntity.COMPACT));
        assertEquals(false, notification.getAttrs().get(UserMessageEntity.COMPACT));

        conversations.rebuildContext("summary of processed mail", execution, CheckpointSaver.NONE);
        assertEquals(List.of("question"), userTexts(execution));
    }

    /** Earlier answered user turns are absorbed by the summary; only the latest user turn survives. */
    @Test
    void summaryRebuildDropsEarlierAnsweredUserMessages() {
        DefaultConversationManager conversations = DefaultConversationManager.builder().contextAttachmentProvider(ContextAttachmentProvider.NONE).build();
        Execution execution = execution(
                SystemMessageEntity.builder().text("LEADING").build(),
                UserMessageEntity.from("user-one"),
                AiMessageEntity.builder().text("answer-one").build(),
                UserMessageEntity.from("user-two"),
                AiMessageEntity.builder().text("answer-two").build(),
                UserMessageEntity.from("user-three"));

        conversations.rebuildContext("compressed summary", execution, CheckpointSaver.NONE);

        assertEquals(List.of("user-three"), userTexts(execution),
                "earlier answered user turns must not be replayed as open user messages");
    }

    /** Case 11 (premise A): the second summarize round sees the previous summary. */
    @Test
    void summaryInputIncludesPreviousSummarySystemMessage() {
        List<ChatRequestEntity> requests = new ArrayList<>();
        DefaultConversationManager conversations = DefaultConversationManager.builder().contextAttachmentProvider(ContextAttachmentProvider.NONE).build();
        StubContextTokenizer tokenizer = new StubContextTokenizer(OVER_THRESHOLD);
        ContextUsageReporter usage = new ContextUsageReporter(tokenizer, 100_000,
                new ContextEventPublisher(List.of()), 1);
        DefaultModelCompacter compacter = DefaultModelCompacter.builder().models(ignored -> request -> {
            requests.add(request);
            return ChatResponseEntity.builder()
                    .aiMessageEntity(AiMessageEntity.builder().text(SUMMARY_JSON).build())
                    .build();
        }).conversationManager(conversations).contextAttachmentProvider(ContextAttachmentProvider.NONE)
                .usage(usage).summaryApplier(new DefaultCompactSummaryApplier(conversations, CheckpointSaver.NONE)).build();
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
                DefaultConversationManager.builder().contextAttachmentProvider(executionId -> Optional.of("ATTACHMENT-STATE-42")).build();
        Execution execution = execution(
                SystemMessageEntity.builder().text("LEADING").build(),
                UserMessageEntity.from("user-one"));

        conversations.rebuildContext("compressed summary", execution, CheckpointSaver.NONE);

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
     * An answered earlier turn is dropped while the latest user message survives without injecting
     * a redundant continue prompt.
     */
    @Test
    void summaryRebuildDropsEarlierTurnEvenWhenTrailingTurnExists() {
        DefaultConversationManager conversations = DefaultConversationManager.builder().contextAttachmentProvider(ContextAttachmentProvider.NONE).build();
        Execution execution = execution(
                SystemMessageEntity.builder().text("LEADING").build(),
                UserMessageEntity.from("user-one"),
                AiMessageEntity.builder().text("answer-one").build(),
                UserMessageEntity.from("user-two"));

        conversations.rebuildContext("compressed summary", execution, CheckpointSaver.NONE);

        assertEquals(List.of("user-two"), userTexts(execution),
                "only the latest user message survives the rebuild");
        boolean injectedContinuePrompt = execution.getMessages().stream()
                .filter(UserMessageEntity.class::isInstance)
                .map(Message::text)
                .anyMatch(text -> text.contains("compacted into the summary above"));
        assertFalse(injectedContinuePrompt,
                "user messages exist, so the bare continue prompt must not be injected");
    }

    // ------------------------------------------------------------------ fixtures

    /** A session built from the given messages, with the first system text as its business prompt. */
    /** The trailing turn is shrunk to its last assistant message when it exceeds the tail budget. */
    @Test
    void oversizedTrailingTurnIsShrunkToItsLastAssistantMessage() {
        // Small cap so the trailing turn (everything after the last user message) clearly exceeds
        // TAIL_COMPACT_THRESHOLD (20%) of it.
        DefaultConversationManager conversations = DefaultConversationManager.builder()
                .tokenizer(new DefaultTokenizer())
                .maxTokens(900)
                .contextAttachmentProvider(ContextAttachmentProvider.NONE)
                .promptAssemblerFactory(SystemPromptAssembler::new)
                .build();
        Execution execution = execution(
                SystemMessageEntity.builder().text("LEADING").build(),
                UserMessageEntity.from("do the work"),
                AiMessageEntity.builder().text("OLDSTEP " + "x".repeat(700)).build(),
                AiMessageEntity.builder().text("NEWSTEP " + "y".repeat(200)).build());

        conversations.rebuildContext("compressed summary", execution, CheckpointSaver.NONE);

        List<String> texts = execution.getMessages().stream().map(Message::text).toList();
        assertFalse(texts.stream().anyMatch(t -> t != null && t.contains("OLDSTEP")),
                "the older assistant turn is dropped when the trailing turn is oversized: " + texts);
        assertTrue(texts.stream().anyMatch(t -> t != null && t.contains("NEWSTEP")),
                "the last assistant message survives: " + texts);
        assertEquals(List.of("do the work"), userTexts(execution),
                "user messages still survive regardless of the tail shrink");
    }

    /** A summary rebuild strips the stale thinking signature from the trailing assistant turn. */
    @Test
    void summaryRebuildStripsTheStaleThinkingSignatureFromTheTrailingTurn() {
        DefaultConversationManager conversations = DefaultConversationManager.builder().contextAttachmentProvider(ContextAttachmentProvider.NONE).build();
        Execution execution = execution(
                SystemMessageEntity.builder().text("LEADING").build(),
                UserMessageEntity.from("continue"),
                AiMessageEntity.builder().text("answer").thinking("OLD-THINKING")
                        .attributes(java.util.Map.of("signature", "OLD-SIG")).build());

        conversations.rebuildContext("compressed summary", execution, CheckpointSaver.NONE);

        AiMessageEntity retained = execution.getMessages().stream()
                .filter(AiMessageEntity.class::isInstance).map(AiMessageEntity.class::cast)
                .findFirst().orElse(null);
        assertNotNull(retained, "the trailing assistant turn survives");
        assertEquals("answer", retained.getText(), "text is preserved");
        assertTrue(retained.getThinking() == null || retained.getThinking().isBlank(),
                "the stale thinking block is removed");
        assertTrue(retained.getAttributes() == null || retained.getAttributes().isEmpty(),
                "the stale thinking signature attribute is removed");
    }

    @Test
    void oversizedToolTrailingTurnKeepsLatestUserAndRecentToolGroupsWithoutDuplicates() {
        DefaultConversationManager conversations = DefaultConversationManager.builder()
                .tokenizer(new DefaultTokenizer())
                .maxTokens(900)
                .contextAttachmentProvider(ContextAttachmentProvider.NONE)
                .promptAssemblerFactory(SystemPromptAssembler::new)
                .build();
        AiMessageEntity tc1 = AiMessageEntity.builder().toolCalls(List.of(new ToolCallRequest("c1", "t", 0, "{}"))).build();
        ToolMessageEntity tr1 = ToolMessageEntity.builder().id("c1").name("t").toolOutput("x".repeat(600)).build();
        AiMessageEntity tc2 = AiMessageEntity.builder().toolCalls(List.of(new ToolCallRequest("c2", "t", 0, "{}"))).build();
        ToolMessageEntity tr2 = ToolMessageEntity.builder().id("c2").name("t").toolOutput("out2").build();
        AiMessageEntity tc3 = AiMessageEntity.builder().toolCalls(List.of(new ToolCallRequest("c3", "t", 0, "{}"))).build();
        ToolMessageEntity tr3 = ToolMessageEntity.builder().id("c3").name("t").toolOutput("out3").build();
        AiMessageEntity tc4 = AiMessageEntity.builder().toolCalls(List.of(new ToolCallRequest("c4", "t", 0, "{}"))).build();
        ToolMessageEntity tr4 = ToolMessageEntity.builder().id("c4").name("t").toolOutput("out4").build();
        UserMessageEntity earlyNotice = UserMessageEntity.from("early notice").markPendingCompaction();
        UserMessageEntity lateNotice = UserMessageEntity.from("late notice").markPendingCompaction();

        Execution execution = execution(
                SystemMessageEntity.builder().text("LEADING").build(),
                UserMessageEntity.from("old question"),
                AiMessageEntity.builder().text("old answer").build(),
                UserMessageEntity.from("current task"),
                tc1, tr1, earlyNotice, tc2, tr2, tc3, tr3, tc4, tr4, lateNotice);

        conversations.rebuildContext("compressed summary", execution, CheckpointSaver.NONE);

        List<Message> rebuilt = execution.getMessages();
        assertEquals(List.of("current task", "late notice"), userTexts(execution),
                "latest real user message survives once and early notice before cut point is dropped");
        assertFalse(rebuilt.contains(tc1), "oldest tool group beyond MAX_GROUP=3 is dropped");
        assertTrue(rebuilt.contains(tc2) && rebuilt.contains(tc3) && rebuilt.contains(tc4),
                "last 3 tool-call groups survive in order");
        assertEquals(1, rebuilt.stream().filter(lateNotice::equals).count(),
                "trailing compact notification must not be duplicated");
    }

    private static Execution execution(Message... messages) {
        List<Message> copy = new ArrayList<>(List.of(messages));
        String systemPrompt = copy.stream()
                .filter(SystemMessageEntity.class::isInstance)
                .map(Message::text)
                .findFirst()
                .orElse("");
        return Execution.builder().id("execution-1")
                .agentRequest(AgentRequest.builder()
                        .contextSpec(ContextSpec.builder()
                                .context(copy)
                                .systemPrompt(systemPrompt)
                                .build())
                        .build())
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
