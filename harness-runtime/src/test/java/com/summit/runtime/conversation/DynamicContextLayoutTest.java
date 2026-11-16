package com.summit.runtime.conversation;

import com.summit.core.agent.AgentRequest;
import com.summit.core.agent.ContextSpec;
import com.summit.core.agent.Execution;
import com.summit.core.compact.ContextAttachmentProvider;
import com.summit.core.conversation.message.AiMessageEntity;
import com.summit.core.conversation.message.ContextSectionKind;
import com.summit.core.conversation.message.Message;
import com.summit.core.conversation.message.SystemMessageEntity;
import com.summit.core.conversation.message.UserMessageEntity;
import com.summit.core.mcp.McpToolScope;
import com.summit.core.workspace.WorkspaceAccessMode;
import com.summit.core.workspace.WorkspaceOptions;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Where the per-round context lands, and what it is allowed to touch.
 *
 * <p>The whole point of splitting dynamic content out of the system prompt is prefix caching: every
 * message already sent must stay byte-identical, so a changed context may only be <b>inserted</b>
 * before the current user turn. These tests pin that, plus the escape hatch that keeps the block
 * from piling up once the history is compacted.</p>
 */
class DynamicContextLayoutTest {

    private final DefaultConversationManager conversations =
            new DefaultConversationManager(ContextAttachmentProvider.NONE);

    @Test
    @DisplayName("多轮续跑：历史一条不动，新动态上下文插在本轮用户消息之前")
    void insertingNeverRewritesTheHistoryPrefix() {
        UserMessageEntity previousContext = contextOf("OLD CONTEXT");
        List<Message> history = new ArrayList<>(List.of(
                SystemMessageEntity.builder().text("SYS").build(),
                previousContext,
                UserMessageEntity.from("first question"),
                AiMessageEntity.builder().text("first answer").build(),
                UserMessageEntity.from("second question")));

        Execution execution = execution(history, requestWithMemory("mem v2"));

        conversations.startConversation(execution, null, McpToolScope.EMPTY);

        List<Message> after = execution.getMessages();
        assertEquals(6, after.size(), "one message was inserted, nothing was dropped");
        for (int i = 0; i < 4; i++) {
            assertSame(history.get(i), after.get(i),
                    "message " + i + " must be the very same object: a rewritten history kills the cache");
        }
        assertTrue(after.get(4) instanceof UserMessageEntity inserted && inserted.hasContextContract(),
                "the new context sits right before the user turn");
        assertSame(history.get(4), after.get(5), "the user turn itself is untouched, only pushed back one slot");
        assertEquals("second question", after.getLast().text(),
                "the user turn stays last: background behind the question splits attention");
    }

    @Test
    @DisplayName("上下文没有变化：不插入、不换列表，缓存前缀继续有效")
    void unchangedContextIsNotReinserted() {
        AgentRequest request = requestWithMemory("mem v1");
        UserMessageEntity sameContext = ContextDynamicAssembler.assemble(
                request.getContextSpec(), McpToolScope.EMPTY, List.of(), request.getTask());
        List<Message> history = new ArrayList<>(List.of(
                SystemMessageEntity.builder().text("SYS").build(),
                sameContext,
                UserMessageEntity.from("question")));

        Execution execution = execution(history, request);
        List<Message> before = execution.getMessages();

        conversations.startConversation(execution, null, McpToolScope.EMPTY);
        conversations.startConversation(execution, null, McpToolScope.EMPTY);

        assertSame(before, execution.getMessages(),
                "nothing changed, so even the list reference is left alone and the token baseline stays valid");
        assertEquals(3, execution.getMessages().size(), "no duplicate context blocks");
    }

    @Test
    @DisplayName("上下文变化：旧的那份留在原位，新的追加在尾部之前")
    void changedContextIsAppendedNotReplaced() {
        UserMessageEntity previousContext = contextOf("OLD CONTEXT");
        List<Message> history = new ArrayList<>(List.of(
                SystemMessageEntity.builder().text("SYS").build(),
                previousContext,
                UserMessageEntity.from("question"),
                AiMessageEntity.builder().text("answer").build(),
                UserMessageEntity.from("next question")));

        Execution execution = execution(history, requestWithMemory("mem v2"));

        conversations.startConversation(execution, null, McpToolScope.EMPTY);

        List<UserMessageEntity> contextMessages = execution.getMessages().stream()
                .filter(UserMessageEntity.class::isInstance)
                .map(UserMessageEntity.class::cast)
                .filter(UserMessageEntity::hasContextContract)
                .toList();
        assertEquals(2, contextMessages.size(), "the stale block is kept in place and a new one is added");
        assertSame(previousContext, contextMessages.getFirst(),
                "the historical block must not be rewritten");
        assertTrue(contextMessages.getLast().text().contains("mem v2"), "the newest block carries the new content");
    }

    @Test
    @DisplayName("压缩：动态上下文只保留最新一份，避免逐轮堆积")
    void compactionKeepsOnlyTheLatestDynamicContext() {
        UserMessageEntity newestContext = contextOf("NEWEST CONTEXT");
        List<Message> history = new ArrayList<>(List.of(
                SystemMessageEntity.builder().text("SYS").build(),
                contextOf("OLDEST CONTEXT"),
                UserMessageEntity.from("first question"),
                AiMessageEntity.builder().text("first answer").build(),
                contextOf("MIDDLE CONTEXT"),
                UserMessageEntity.from("second question"),
                AiMessageEntity.builder().text("second answer").build(),
                newestContext,
                UserMessageEntity.from("third question")));

        Execution execution = execution(history, requestWithMemory("unused"));

        conversations.rebuildContext("summary", execution);

        List<UserMessageEntity> remaining = execution.getMessages().stream()
                .filter(UserMessageEntity.class::isInstance)
                .map(UserMessageEntity.class::cast)
                .filter(UserMessageEntity::hasContextContract)
                .toList();
        assertEquals(1, remaining.size(), "a rebuild must collapse the dynamic context to a single copy");
        assertSame(newestContext, remaining.getFirst(), "the surviving copy is the newest one");
    }

    @Test
    @DisplayName("护栏只在上限被跨过时告警：内容不变的多轮不刷屏，且一如既往地继续追加")
    void pileUpWarningFiresWhenTheLimitIsCrossedNotOnEveryRound() {
        Logger logger = (Logger) LoggerFactory.getLogger(DefaultConversationManager.class);
        ListAppender<ILoggingEvent> appender = new ListAppender<>();
        appender.start();
        logger.addAppender(appender);
        try {
            // Four live blocks already in history; the request renders a different one.
            List<Message> history = new ArrayList<>(List.of(
                    SystemMessageEntity.builder().text("SYS").build(),
                    contextOf("CONTEXT-1"),
                    contextOf("CONTEXT-2"),
                    contextOf("CONTEXT-3"),
                    contextOf("CONTEXT-4"),
                    UserMessageEntity.from("question")));
            Execution execution = execution(history, requestWithMemory("CONTEXT-5"));

            // The fifth block crosses the default limit of four: one warning, and it is appended.
            conversations.startConversation(execution, null, McpToolScope.EMPTY);
            assertEquals(5, liveContextBlocks(execution), "the block is still appended: the guard only reports");
            assertEquals(1, warningCount(appender), "crossing the limit warns once");

            // Nothing changed: no insert, so no repeated warning on the following rounds.
            conversations.startConversation(execution, null, McpToolScope.EMPTY);
            conversations.startConversation(execution, null, McpToolScope.EMPTY);
            assertEquals(5, liveContextBlocks(execution), "an unchanged context is not appended again");
            assertEquals(1, warningCount(appender),
                    "an unchanged history sitting at the limit must not warn on every round");
        } finally {
            logger.detachAppender(appender);
        }
    }

    @Test
    @DisplayName("多轮按类别增量追加：未变类别不重复塞入，跨轮未变也不重复插入")
    void multiTurnAppendsOnlyChangedCategories() {
        AgentRequest round1Request = AgentRequest.builder()
                .contextSpec(ContextSpec.builder().memory(List.of("mem v1")).build())
                .workspaceOptions(WorkspaceOptions.builder()
                        .accessMode(WorkspaceAccessMode.READ_ONLY)
                        .environment(Map.of("ENV_A", "1", "API_TOKEN", "secret"))
                        .build())
                .task(List.of("step 1"))
                .build();

        List<Message> history = new ArrayList<>(List.of(UserMessageEntity.from("turn 1")));
        Execution execution = execution(history, round1Request);
        conversations.startConversation(execution, null, McpToolScope.EMPTY);

        // Simulate assistant reply and turn 2 with identical context
        execution.getMessages().add(AiMessageEntity.builder().text("reply 1").build());
        execution.getMessages().add(UserMessageEntity.from("turn 2"));
        List<Message> beforeUnchanged = execution.getMessages();
        conversations.startConversation(execution, null, McpToolScope.EMPTY);
        assertSame(beforeUnchanged, execution.getMessages(),
                "multi-turn with unchanged sections must not re-insert <context>");

        // Now change only accessMode and clear task; memory and environment stay unchanged
        execution.setAgentRequest(AgentRequest.builder()
                .contextSpec(ContextSpec.builder().memory(List.of("mem v1")).build())
                .workspaceOptions(WorkspaceOptions.builder()
                        .accessMode(WorkspaceAccessMode.READ_WRITE)
                        .environment(Map.of("ENV_A", "1", "API_TOKEN", "secret"))
                        .build())
                .build());
        conversations.startConversation(execution, null, McpToolScope.EMPTY);

        UserMessageEntity delta = assertInstanceOf(UserMessageEntity.class,
                execution.getMessages().get(execution.getMessages().size() - 2));
        assertTrue(delta.hasContextContract());
        assertEquals(List.of(ContextSectionKind.PERMISSION, ContextSectionKind.TASK),
                delta.contextSections().stream().map(m -> m.kind()).toList());
        assertTrue(delta.text().contains("Access mode: READ_WRITE"));
        assertTrue(delta.text().contains("## Task Manifest\n(none)"));
        assertFalse(delta.text().contains("## Memory Prompt"));
        assertFalse(delta.text().contains("## Workspace Environment"));

        // Turn 3 with task still empty must not re-emit (none)
        execution.getMessages().add(AiMessageEntity.builder().text("reply 2").build());
        execution.getMessages().add(UserMessageEntity.from("turn 3"));
        List<Message> beforeTurn3 = execution.getMessages();
        conversations.startConversation(execution, null, McpToolScope.EMPTY);
        assertSame(beforeTurn3, execution.getMessages(), "already-cleared section is not emitted again");
    }

    @Test
    @DisplayName("压缩合并：多轮增量追加的不同类别在压缩后合并为一条完整上下文")
    void compactionMergesLatestSectionsAcrossIncrementalContextMessages() {
        UserMessageEntity first = ContextDynamicAssembler.assemble(
                ContextSpec.builder().memory(List.of("mem v1")).build(),
                McpToolScope.EMPTY, List.of(),
                WorkspaceOptions.builder().accessMode(WorkspaceAccessMode.READ_ONLY).build(),
                List.of("old task"));
        UserMessageEntity second = ContextDynamicAssembler.assemble(
                ContextSpec.builder().build(),
                McpToolScope.EMPTY, List.of(),
                WorkspaceOptions.builder().accessMode(WorkspaceAccessMode.READ_WRITE).build(),
                null);

        List<Message> history = new ArrayList<>(List.of(
                SystemMessageEntity.builder().text("SYS").build(),
                first,
                UserMessageEntity.from("q1"),
                AiMessageEntity.builder().text("a1").build(),
                second,
                UserMessageEntity.from("q2")));

        Execution execution = execution(history, AgentRequest.builder().build());
        conversations.rebuildContext("summary", execution);

        List<UserMessageEntity> remaining = execution.getMessages().stream()
                .filter(UserMessageEntity.class::isInstance)
                .map(UserMessageEntity.class::cast)
                .filter(UserMessageEntity::hasContextContract)
                .toList();
        assertEquals(1, remaining.size());
        String merged = remaining.getFirst().text();
        assertTrue(merged.contains("## Memory Prompt\nmem v1"), merged);
        assertTrue(merged.contains("## Workspace Permission\nAccess mode: READ_WRITE"), merged);
        assertFalse(merged.contains("READ_ONLY"), merged);
        assertTrue(merged.contains("## Task Manifest\nold task"), merged);
    }

    private static long liveContextBlocks(Execution execution) {
        return execution.getMessages().stream()
                .filter(UserMessageEntity.class::isInstance)
                .map(UserMessageEntity.class::cast)
                .filter(UserMessageEntity::hasContextContract)
                .count();
    }

    // --- helpers ---------------------------------------------------------------------------

    private static UserMessageEntity contextOf(String text) {
        return UserMessageEntity.from(text).markContextContract();
    }

    private static AgentRequest requestWithMemory(String memory) {
        return AgentRequest.builder()
                .contextSpec(ContextSpec.builder().memory(List.of(memory)).build())
                .build();
    }

    private static long warningCount(ListAppender<ILoggingEvent> appender) {
        return appender.list.stream().filter(event -> event.getLevel().toString().equals("WARN")).count();
    }

    private static Execution execution(List<Message> messages, AgentRequest request) {
        return Execution.builder()
                .id("execution-1").agentId("agent-1")
                .messages(messages)
                .agentRequest(request)
                .build();
    }
}
