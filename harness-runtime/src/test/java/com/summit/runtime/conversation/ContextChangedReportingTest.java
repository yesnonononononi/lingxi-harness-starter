package com.summit.runtime.conversation;

import com.summit.core.agent.AgentRequest;
import com.summit.core.agent.ContextSpec;
import com.summit.core.agent.Execution;
import com.summit.core.compact.ContextAttachmentProvider;
import com.summit.core.conversation.ChangedDiff;
import com.summit.core.conversation.event.AgentEventPublisher;
import com.summit.core.conversation.event.AgentMessageEvent;
import com.summit.core.conversation.event.ContextChangedEvent;
import com.summit.core.conversation.event.ContextChangeReason;
import com.summit.core.conversation.event.ContextEventPublisher;
import com.summit.core.conversation.event.ContextListener;
import com.summit.core.conversation.event.ContextRebuiltEvent;
import com.summit.core.conversation.message.AiMessageEntity;
import com.summit.core.conversation.message.ContextSectionKind;
import com.summit.core.conversation.message.Message;
import com.summit.core.conversation.message.SystemMessageEntity;
import com.summit.core.conversation.message.UserMessageEntity;
import com.summit.core.mcp.McpToolScope;
import com.summit.core.runtime.AgentStreamListener;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import com.summit.core.conversation.CheckpointSaver;

/**
 * The manager reports its own changes, and only when there is a change to report.
 *
 * <p>The message list the model is about to receive belongs to this manager, so the fact that it
 * changed is published from here — through the context surface the manager was built with. A
 * listener therefore always reads back the state the event describes, and the "nothing changed"
 * round publishes nothing at all instead of dispatching a {@code null}.</p>
 */
class ContextChangedReportingTest {

    private static final class Recorder implements ContextListener {
        private final List<ContextChangedEvent> changes = new ArrayList<>();
        private final List<ContextRebuiltEvent> rebuilds = new ArrayList<>();

        @Override
        public int order() {
            return 0;
        }

        @Override
        public void onContextChanged(ContextChangedEvent event) {
            changes.add(event);
        }

        @Override
        public void onContextRebuilt(ContextRebuiltEvent event) {
            rebuilds.add(event);
        }
    }

    private final Recorder recorder = new Recorder();
    private final DefaultConversationManager conversations = DefaultConversationManager.builder()
            .contextAttachmentProvider(ContextAttachmentProvider.NONE)
            .contextEventPublisher(new ContextEventPublisher(List.of(recorder)))
            .build();

    @Test
    void anUnchangedRoundPublishesNothingAndLeavesTheListAlone() {
        AgentRequest request = requestWithMemory("SYS", "mem v1");
        UserMessageEntity sameContext = ContextDynamicAssembler.builder()
                .contextSpec(request.getContextSpec())
                .scope(McpToolScope.EMPTY)
                .skills(List.of())
                .tasks(request.getTask())
                .build();
        List<Message> history = new ArrayList<>(List.of(
                SystemMessageEntity.builder().text("## Business Prompt\nSYS").build(),
                sameContext,
                UserMessageEntity.from("question")));
        Execution execution = execution(history, request);
        List<Message> before = execution.getMessages();

        conversations.startConversation(execution, null, McpToolScope.EMPTY, CheckpointSaver.NONE);
        execution.getMessages().add(AiMessageEntity.builder().text("answer").build());
        execution.getMessages().add(UserMessageEntity.from("next question"));
        List<Message> beforeSecond = execution.getMessages();
        conversations.startConversation(execution, null, McpToolScope.EMPTY, CheckpointSaver.NONE);

        assertTrue(recorder.changes.isEmpty(), "a round that changed nothing must not publish a change");
        assertSame(beforeSecond, execution.getMessages(),
                "the list reference is left alone so the incremental token baseline stays valid");
        assertEquals(5, execution.getMessages().size(), "no duplicate context block was inserted");
    }

    @Test
    void theFirstRoundPublishesAnInsertedSystemPromptAndFreshSections() {
        Execution execution = execution(new ArrayList<>(List.of(UserMessageEntity.from("question"))),
                requestWithMemory("SYS", "mem v1"));

        conversations.startConversation(execution, null, McpToolScope.EMPTY, CheckpointSaver.NONE);

        ContextChangedEvent event = only(recorder.changes);
        assertEquals("execution-1", event.executionId());
        assertTrue(event.getReasons().contains(ContextChangeReason.SYSTEM_PROMPT_INSERTED),
                "the leading system prompt did not exist before this round");
        assertTrue(event.getReasons().contains(ContextChangeReason.DYNAMIC_SECTIONS_CHANGED));
        assertFalse(event.prefixPreserved(), "a first round has no cached prefix to preserve");
        assertNull(event.getChangedSections().get(ContextSectionKind.MEMORY).oldContent(),
                "a section that did not exist before has no old side to diff against");
    }

    /** The event must describe the state a listener can already read back. */
    @Test
    void theChangeIsPublishedOnlyAfterTheMessageListIsFinal() {
        List<Object> observedFirstMessage = new ArrayList<>();
        ContextEventPublisher publisher = new ContextEventPublisher(List.of(new ContextListener() {
            @Override
            public int order() {
                return 0;
            }

            @Override
            public void onContextChanged(ContextChangedEvent event) {
                observedFirstMessage.add(event.getReasons());
            }
        }));
        DefaultConversationManager manager = DefaultConversationManager.builder()
                .contextAttachmentProvider(ContextAttachmentProvider.NONE)
                .contextEventPublisher(publisher)
                .build();
        Execution execution = execution(new ArrayList<>(List.of(UserMessageEntity.from("question"))),
                requestWithMemory("SYS", "mem v1"));

        manager.startConversation(execution, null, McpToolScope.EMPTY, CheckpointSaver.NONE);

        assertEquals(1, observedFirstMessage.size(), "the fact is published during the call that made it");
        assertTrue(execution.getMessages().getFirst() instanceof SystemMessageEntity,
                "the list the event describes is already in place when the listener runs");
    }

    @Test
    void aRewrittenBusinessPromptIsPublishedAsAPrefixBreak() {
        List<Message> history = new ArrayList<>(List.of(
                SystemMessageEntity.builder().text("## Business Prompt\nv1").build(),
                UserMessageEntity.from("q1"),
                AiMessageEntity.builder().text("a1").build(),
                UserMessageEntity.from("q2")));
        Execution execution = execution(history, AgentRequest.builder()
                .contextSpec(ContextSpec.builder().systemPrompt("v2").build())
                .build());

        conversations.startConversation(execution, null, McpToolScope.EMPTY, CheckpointSaver.NONE);

        ContextChangedEvent event = only(recorder.changes);
        assertTrue(event.getReasons().contains(ContextChangeReason.SYSTEM_PROMPT_REPLACED));
        assertFalse(event.prefixPreserved(), "the leading system prompt changed, so every cached token is gone");
        SystemMessageEntity leading = (SystemMessageEntity) execution.getMessages().getFirst();
        assertTrue(leading.getText().contains("v2"), "the new prompt is applied honestly, not faked: " + leading.getText());
        for (ChangedDiff diff : event.getChangedSections().values()) {
            assertTrue(diff.newContent() != null, "every reported patch has a new side");
        }
    }

    @Test
    void aRebuildPublishesItsOwnEventDuringTheCall() {
        List<Message> history = new ArrayList<>(List.of(
                SystemMessageEntity.builder().text("SYS").build(),
                UserMessageEntity.from("q1"),
                AiMessageEntity.builder().text("a1").build(),
                UserMessageEntity.from("q2")));
        Execution execution = execution(history, AgentRequest.builder().build());

        conversations.rebuildContext("summary", execution, CheckpointSaver.NONE);

        ContextRebuiltEvent event = only(recorder.rebuilds);
        assertEquals("CONTEXT_REBUILT", event.type());
        assertEquals(execution.getMessages().size(), event.getMessageCount(),
                "the reported count is the session a listener can already read");
        assertTrue(event.getSummaryGeneration() >= 1, "the first summary of a session is generation one");
        assertTrue(recorder.changes.isEmpty(), "a rebuild is its own fact, not an ordinary change");
    }

    @Test
    void aBlankSummaryIsNotARebuildAndPublishesNothing() {
        Execution execution = execution(new ArrayList<>(List.of(UserMessageEntity.from("q1"))),
                AgentRequest.builder().build());
        List<Message> before = execution.getMessages();

        conversations.rebuildContext("   ", execution, CheckpointSaver.NONE);

        assertTrue(recorder.rebuilds.isEmpty());
        assertSame(before, execution.getMessages(), "an unusable summary must not touch the history");
    }

    @Test
    void aManagerBuiltWithoutListenersReportsIntoNothingInsteadOfFailing() {
        DefaultConversationManager bare = DefaultConversationManager.builder()
                .contextAttachmentProvider(ContextAttachmentProvider.NONE)
                .build();
        Execution execution = execution(new ArrayList<>(List.of(UserMessageEntity.from("question"))),
                requestWithMemory("SYS", "mem v1"));

        bare.startConversation(execution, null, McpToolScope.EMPTY, CheckpointSaver.NONE);

        assertTrue(execution.getMessages().getFirst() instanceof SystemMessageEntity);
    }

    @Test
    void theContextSurfaceAndTheStreamingSurfaceStaySeparate() {
        List<String> contextTrail = new ArrayList<>();
        List<String> streamTrail = new ArrayList<>();
        ContextEventPublisher contextEvents = new ContextEventPublisher(List.of(new ContextListener() {
            @Override
            public int order() {
                return 0;
            }

            @Override
            public void onContextChanged(ContextChangedEvent event) {
                contextTrail.add(event.type());
            }
        }));
        AgentEventPublisher agentEvents = new AgentEventPublisher(List.of(new AgentStreamListener() {
            @Override
            public int order() {
                return 0;
            }

            @Override
            public void onAiMessage(AgentMessageEvent event) {
                streamTrail.add(event.type());
            }
        }));

        contextEvents.onContextChanged(ContextChangedEvent.builder().executionId("execution").build());

        assertEquals(List.of("CONTEXT_CHANGED"), contextTrail);
        assertTrue(streamTrail.isEmpty(), "a context fact never reaches the streaming surface");
    }

    // --- checkpoint ordering ---------------------------------------------------------------

    /**
     * The fact is only worth reading as authoritative if the session it describes is already stored:
     * the checkpoint has to run, and finish, before the listener is called.
     */
    @Test
    void theChangeIsPublishedOnlyAfterTheCheckpointIsSaved() {
        List<String> trail = new ArrayList<>();
        DefaultConversationManager manager = DefaultConversationManager.builder()
                .contextAttachmentProvider(ContextAttachmentProvider.NONE)
                .contextEventPublisher(new ContextEventPublisher(List.of(new ContextListener() {
                    @Override
                    public int order() {
                        return 0;
                    }

                    @Override
                    public void onContextChanged(ContextChangedEvent event) {
                        trail.add("published");
                    }
                })))
                .build();
        Execution execution = execution(new ArrayList<>(List.of(UserMessageEntity.from("question"))),
                requestWithMemory("SYS", "mem v1"));

        manager.startConversation(execution, null, McpToolScope.EMPTY,
                stored -> trail.add("saved:" + stored.getMessages().size()));

        assertEquals(List.of("saved:3", "published"), trail,
                "the checkpoint sees the final message list, and the event comes after it");
    }

    @Test
    void aRebuildIsCheckpointedBeforeItIsPublished() {
        List<String> trail = new ArrayList<>();
        DefaultConversationManager manager = DefaultConversationManager.builder()
                .contextAttachmentProvider(ContextAttachmentProvider.NONE)
                .contextEventPublisher(new ContextEventPublisher(List.of(new ContextListener() {
                    @Override
                    public int order() {
                        return 0;
                    }

                    @Override
                    public void onContextRebuilt(ContextRebuiltEvent event) {
                        trail.add("published:" + event.getMessageCount());
                    }
                })))
                .build();
        Execution execution = execution(new ArrayList<>(List.of(
                SystemMessageEntity.builder().text("SYS").build(),
                UserMessageEntity.from("q1"),
                AiMessageEntity.builder().text("a1").build(),
                UserMessageEntity.from("q2"))), AgentRequest.builder().build());

        manager.rebuildContext("summary", execution, stored -> trail.add("saved:" + stored.getMessages().size()));

        assertEquals(2, trail.size());
        assertTrue(trail.getFirst().startsWith("saved:"), "the checkpoint runs first: " + trail);
        assertTrue(trail.getLast().startsWith("published:"), "and the fact follows it: " + trail);
    }

    /**
     * A session that failed to save must not be announced: the event would be a fact about a run
     * that does not exist in the store. The failure belongs to the caller, so it is rethrown.
     */
    @Test
    void aFailedCheckpointPublishesNothingAndPropagates() {
        Execution execution = execution(new ArrayList<>(List.of(UserMessageEntity.from("question"))),
                requestWithMemory("SYS", "mem v1"));

        IllegalStateException failure = assertThrows(IllegalStateException.class,
                () -> conversations.startConversation(execution, null, McpToolScope.EMPTY, stored -> {
                    throw new IllegalStateException("store is down");
                }));

        assertEquals("store is down", failure.getMessage());
        assertTrue(recorder.changes.isEmpty(), "no fact about a session that was never stored");
    }

    @Test
    void aFailedCheckpointOnRebuildPublishesNothing() {
        Execution execution = execution(new ArrayList<>(List.of(
                SystemMessageEntity.builder().text("SYS").build(),
                UserMessageEntity.from("q1"),
                AiMessageEntity.builder().text("a1").build(),
                UserMessageEntity.from("q2"))), AgentRequest.builder().build());

        assertThrows(IllegalStateException.class,
                () -> conversations.rebuildContext("summary", execution, stored -> {
                    throw new IllegalStateException("store is down");
                }));

        assertTrue(recorder.rebuilds.isEmpty());
    }

    @Test
    void aRoundWithoutAFactDoesNotTouchTheCheckpoint() {
        List<String> saved = new ArrayList<>();
        AgentRequest request = requestWithMemory("SYS", "mem v1");
        UserMessageEntity sameContext = ContextDynamicAssembler.builder()
                .contextSpec(request.getContextSpec())
                .scope(McpToolScope.EMPTY)
                .skills(List.of())
                .tasks(request.getTask())
                .build();
        List<Message> history = new ArrayList<>(List.of(
                SystemMessageEntity.builder().text("## Business Prompt\nSYS").build(),
                sameContext,
                UserMessageEntity.from("question")));
        Execution execution = execution(history, request);

        conversations.startConversation(execution, null, McpToolScope.EMPTY,
                stored -> saved.add(stored.getId()));
        conversations.rebuildContext("  ", execution, stored -> saved.add(stored.getId()));

        assertTrue(saved.isEmpty(),
                "nothing is published on this path, so there is nothing to store on the way to publishing");
    }

    /**
     * A fact's identity is its revision, not its payload: the same reasons and the same changed
     * kinds can describe two different revisions, and only the revision tells them apart.
     */
    @Test
    void everyFactCarriesItsOwnRevision() {
        Execution execution = execution(new ArrayList<>(List.of(UserMessageEntity.from("question"))),
                requestWithMemory("SYS", "mem v1"));

        conversations.startConversation(execution, null, McpToolScope.EMPTY, CheckpointSaver.NONE);
        execution.getMessages().add(AiMessageEntity.builder().text("answer").build());
        execution.getMessages().add(UserMessageEntity.from("next question"));
        execution.setAgentRequest(requestWithMemory("SYS", "mem v2"));
        conversations.startConversation(execution, null, McpToolScope.EMPTY, CheckpointSaver.NONE);

        assertEquals(2, recorder.changes.size(), "two rounds that changed the context are two facts");
        assertEquals(1, recorder.changes.getFirst().getContextRevision());
        assertEquals(2, recorder.changes.getLast().getContextRevision());
        assertEquals(recorder.changes.getLast().getContextRevision(), execution.getContextRevision(),
                "the number a fact announces is the one already stored with the context it describes");
    }

    /** A rebuild is a revision of the same context, so it takes its number from the same counter. */
    @Test
    void aRebuildTakesTheNextRevisionFromTheSameCounter() {
        Execution execution = execution(new ArrayList<>(List.of(UserMessageEntity.from("question"))),
                requestWithMemory("SYS", "mem v1"));
        conversations.startConversation(execution, null, McpToolScope.EMPTY, CheckpointSaver.NONE);

        conversations.rebuildContext("summary", execution, CheckpointSaver.NONE);

        assertEquals(1, only(recorder.changes).getContextRevision());
        assertEquals(2, only(recorder.rebuilds).getContextRevision());
        assertEquals(2, execution.getContextRevision());
    }

    /** A round that changes nothing takes no number, so the revisions stay gapless. */
    @Test
    void anUnchangedRoundTakesNoRevision() {
        AgentRequest request = requestWithMemory("SYS", "mem v1");
        UserMessageEntity sameContext = ContextDynamicAssembler.builder()
                .contextSpec(request.getContextSpec())
                .scope(McpToolScope.EMPTY)
                .skills(List.of())
                .tasks(request.getTask())
                .build();
        List<Message> history = new ArrayList<>(List.of(
                SystemMessageEntity.builder().text("## Business Prompt\nSYS").build(),
                sameContext,
                UserMessageEntity.from("question")));
        Execution execution = execution(history, request);

        conversations.startConversation(execution, null, McpToolScope.EMPTY, CheckpointSaver.NONE);

        assertTrue(recorder.changes.isEmpty(), "nothing happened, so nothing is numbered");
        assertEquals(0, execution.getContextRevision());
    }

    private static <T> T only(List<T> events) {
        assertEquals(1, events.size(), "exactly one fact for one change");
        return events.getFirst();
    }

    private static AgentRequest requestWithMemory(String systemPrompt, String memory) {
        return AgentRequest.builder()
                .contextSpec(ContextSpec.builder().systemPrompt(systemPrompt).memory(List.of(memory)).build())
                .build();
    }

    private static Execution execution(List<Message> messages, AgentRequest request) {
        return Execution.builder()
                .id("execution-1").agentId("agent-1")
                .messages(messages)
                .agentRequest(request)
                .build();
    }
}
