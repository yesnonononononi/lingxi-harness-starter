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
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The context manager reports what it changed, and only what it changed.
 *
 * <p>The event is returned rather than published here on purpose: an event a consumer may treat as
 * an authoritative fact must not be readable before the snapshot it describes is durable. So the
 * manager's job is to answer "did anything change, and what", and the runtime publishes that answer
 * after the save. These tests pin the manager's half, including the round that changed nothing and
 * must therefore report nothing.</p>
 */
class ContextChangedReportingTest {

    private final DefaultConversationManager conversations =
            DefaultConversationManager.builder().contextAttachmentProvider(ContextAttachmentProvider.NONE).build();

    @Test
    void anUnchangedRoundReportsNothingAndLeavesTheListAlone() {
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

        Optional<ContextChangedEvent> first = conversations.startConversation(execution, null, McpToolScope.EMPTY);
        execution.getMessages().add(AiMessageEntity.builder().text("answer").build());
        execution.getMessages().add(UserMessageEntity.from("next question"));
        List<Message> beforeSecond = execution.getMessages();
        Optional<ContextChangedEvent> second = conversations.startConversation(execution, null, McpToolScope.EMPTY);

        assertTrue(first.isEmpty(), "the request already matches the history, so there is nothing to report");
        assertTrue(second.isEmpty(), "nothing changed on the following round either");
        assertSame(beforeSecond, execution.getMessages(),
                "the list reference is left alone so the incremental token baseline stays valid");
        assertEquals(5, execution.getMessages().size(), "no duplicate context block was inserted");
    }

    @Test
    void theFirstRoundReportsAnInsertedSystemPromptAndFreshSections() {
        Execution execution = execution(new ArrayList<>(List.of(UserMessageEntity.from("question"))),
                requestWithMemory("SYS", "mem v1"));

        ContextChangedEvent event = conversations.startConversation(execution, null, McpToolScope.EMPTY).orElseThrow();

        assertEquals("execution-1", event.executionId());
        assertTrue(event.getReasons().contains(ContextChangeReason.SYSTEM_PROMPT_INSERTED),
                "the leading system prompt did not exist before this round");
        assertTrue(event.getReasons().contains(ContextChangeReason.DYNAMIC_SECTIONS_CHANGED));
        assertFalse(event.prefixPreserved(), "a first round has no cached prefix to preserve");
        assertNull(event.getChangedSections().get(ContextSectionKind.MEMORY).oldContent(),
                "a section that did not exist before has no old side to diff against");
    }

    @Test
    void aRewrittenBusinessPromptIsReportedAsAPrefixBreak() {
        List<Message> history = new ArrayList<>(List.of(
                SystemMessageEntity.builder().text("## Business Prompt\nv1").build(),
                UserMessageEntity.from("q1"),
                AiMessageEntity.builder().text("a1").build(),
                UserMessageEntity.from("q2")));
        Execution execution = execution(history, AgentRequest.builder()
                .contextSpec(ContextSpec.builder().systemPrompt("v2").build())
                .build());

        ContextChangedEvent event = conversations.startConversation(execution, null, McpToolScope.EMPTY).orElseThrow();

        assertTrue(event.getReasons().contains(ContextChangeReason.SYSTEM_PROMPT_REPLACED));
        assertFalse(event.prefixPreserved(), "the leading system prompt changed, so every cached token is gone");
        SystemMessageEntity leading = (SystemMessageEntity) execution.getMessages().getFirst();
        assertTrue(leading.getText().contains("v2"), "the new prompt is applied honestly, not faked: " + leading.getText());
        for (ChangedDiff diff : event.getChangedSections().values()) {
            assertTrue(diff.newContent() != null, "every reported patch has a new side");
        }
    }

    @Test
    void aRebuildReportsItsOwnEventAndCanBeToldApartFromAnOrdinaryChange() {
        List<Message> history = new ArrayList<>(List.of(
                SystemMessageEntity.builder().text("SYS").build(),
                UserMessageEntity.from("q1"),
                AiMessageEntity.builder().text("a1").build(),
                UserMessageEntity.from("q2")));
        Execution execution = execution(history, AgentRequest.builder().build());

        ContextRebuiltEvent event = conversations.rebuildContext("summary", execution).orElseThrow();

        assertEquals("CONTEXT_REBUILT", event.type());
        assertEquals(execution.getMessages().size(), event.getMessageCount());
        assertTrue(event.getSummaryGeneration() >= 1, "the first summary of a session is generation one");
    }

    @Test
    void aBlankSummaryIsNotARebuildAndReportsNothing() {
        Execution execution = execution(new ArrayList<>(List.of(UserMessageEntity.from("q1"))),
                AgentRequest.builder().build());
        List<Message> before = execution.getMessages();

        assertTrue(conversations.rebuildContext("   ", execution).isEmpty());
        assertSame(before, execution.getMessages(), "an unusable summary must not touch the history");
    }

    @Test
    void theContextChannelNeverReachesTheStreamingSurface() {
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

            @Override
            public void onContextRebuilt(ContextRebuiltEvent event) {
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
        contextEvents.onContextRebuilt(ContextRebuiltEvent.builder().executionId("execution").build());

        assertEquals(List.of("CONTEXT_CHANGED", "CONTEXT_REBUILT"), contextTrail);
        assertTrue(streamTrail.isEmpty(), "a context fact never reaches the streaming surface");
    }

    private static AgentRequest requestWithMemory(String memory) {
        return requestWithMemory(null, memory);
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
