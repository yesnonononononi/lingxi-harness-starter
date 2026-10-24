package com.summit.runtime.compact;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.summit.core.agent.AgentRequest;
import com.summit.core.agent.Execution;
import com.summit.core.compact.ContextCompactRequest;
import com.summit.core.compact.ContextUsageMetric;
import com.summit.core.compact.Tokenizer;
import com.summit.core.conversation.api.ToolCallRequest;
import com.summit.core.conversation.event.ContextUpdateEvent;
import com.summit.core.conversation.event.RuntimeEventPublisher;
import com.summit.core.conversation.message.AiMessageEntity;
import com.summit.core.conversation.message.Message;
import com.summit.core.conversation.message.SystemMessageEntity;
import com.summit.core.conversation.message.ToolMessageEntity;
import com.summit.core.conversation.message.UserMessageEntity;
import com.summit.core.runtime.RuntimeListener;
import com.summit.core.runtime.loop.ContextUsageReporter;
import com.summit.runtime.agent.AgentConfig;
import com.summit.runtime.conversation.DefaultTokenizer;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The prune band's segment model, levels and exit conditions — cases 13 to 26 and 31 of §8.2.
 *
 * <p>Token arithmetic uses {@link DefaultTokenizer} (about three characters per token), so the
 * payload sizes below are chosen to land a segment on a specific level instead of guessing.</p>
 */
class DefaultContextPrunerTest {
    private static final int MAX_TOKENS = 10_000;
    /** Budget too small for anything: every gradable segment is dropped. */
    private static final double TINY_RATIO = 0.0001d;
    /** Budget large enough that nothing is trimmed. */
    private static final double GENEROUS_RATIO = 1.0d;

    private final DefaultTokenizer tokenizer = new DefaultTokenizer();
    private final List<ContextUpdateEvent> events = new ArrayList<>();

    /** Case 13: pruning keeps every user message, in order. */
    @Test
    void pruneKeepsAllUserMessages() {
        Execution execution = execution(
                SystemMessageEntity.builder().text("system prompt").build(),
                UserMessageEntity.from("first user turn"),
                ai(null, "c1"),
                tool("c1", "x".repeat(3_000), null),
                UserMessageEntity.from("second user turn"),
                UserMessageEntity.from("third user turn"));

        assertTrue(pruner(TINY_RATIO).compact(new ContextCompactRequest(execution, null)));

        assertEquals(List.of("system prompt", "first user turn", "second user turn", "third user turn"),
                textsOf(execution), "user messages are never trimmed");
    }

    /** Case 14: every system message survives, in order, ahead of the rest. */
    @Test
    void pruneKeepsAllSystemMessagesIncludingSummary() {
        Execution execution = execution(
                SystemMessageEntity.builder().text("leading system").build(),
                SystemMessageEntity.builder().text("generated summary").build(),
                UserMessageEntity.from("question"),
                ai(null, "c1"),
                tool("c1", "x".repeat(3_000), null));

        assertTrue(pruner(TINY_RATIO).compact(new ContextCompactRequest(execution, null)));

        assertEquals(List.of("leading system", "generated summary"), execution.getMessages().stream()
                        .filter(SystemMessageEntity.class::isInstance).map(Message::text).toList(),
                "both system messages survive and stay first");
    }

    /** Case 15: ownership is resolved by tool-call id, not by adjacency. */
    @Test
    void segmentOwnershipResolvesByToolCallIdNotAdjacency() {
        // The two results of one assistant message arrive in separate batches, with a user turn in
        // between. Adjacency would orphan the second result and drop it.
        List<Message> originals = new ArrayList<>(List.of(
                SystemMessageEntity.builder().text("system").build(),
                AiMessageEntity.builder().toolCalls(List.of(
                        new ToolCallRequest("c1", "read", 0, "{}"),
                        new ToolCallRequest("c2", "read", 1, "{}"))).build(),
                tool("c1", "a".repeat(300), null),
                UserMessageEntity.from("continue please"),
                tool("c2", "b".repeat(300), null)));
        Execution execution = execution(originals.toArray(new Message[0]));

        pruner(GENEROUS_RATIO).compact(new ContextCompactRequest(execution, null));

        assertEquals(5, execution.getMessages().size(),
                "both results belong to the same assistant message and none is an orphan");
        for (int i = 0; i < originals.size(); i++) {
            assertSame(originals.get(i), execution.getMessages().get(i),
                    "an ungradeable (interleaved) group is kept verbatim and in place");
        }
    }

    /** Case 16: an orphan tool result is dropped with a warning. */
    @Test
    void orphanToolMessageIsDroppedWithWarn() {
        Logger logger = (Logger) LoggerFactory.getLogger(DefaultContextPruner.class);
        ListAppender<ILoggingEvent> appender = new ListAppender<>();
        appender.start();
        logger.addAppender(appender);
        try {
            Execution execution = execution(
                    SystemMessageEntity.builder().text("system").build(),
                    ai(null, "c1"),
                    tool("c1", "x".repeat(30), null),
                    tool("ghost", "y".repeat(30), null));

            assertTrue(pruner(GENEROUS_RATIO).compact(new ContextCompactRequest(execution, null)));

            assertEquals(3, execution.getMessages().size(), "the orphan is not part of the product");
            assertFalse(textsOf(execution).contains("y".repeat(30)), "the orphan payload is gone");
            assertTrue(appender.list.stream()
                            .anyMatch(event -> event.getLevel() == Level.WARN
                                    && event.getFormattedMessage().contains("orphan")),
                    "dropping an orphan must be reported");
        } finally {
            logger.detachAppender(appender);
        }
    }

    /** Case 17: a plain-text assistant message takes part in grading like any other segment. */
    @Test
    void pruneHandlesPlainAssistantMessages() {
        Execution dropped = execution(
                SystemMessageEntity.builder().text("system").build(),
                UserMessageEntity.from("question"),
                AiMessageEntity.builder().text("a plain answer").build());
        assertTrue(pruner(TINY_RATIO).compact(new ContextCompactRequest(dropped, null)));
        assertFalse(textsOf(dropped).contains("a plain answer"), "DROP omits the plain-text segment");

        Execution kept = execution(
                SystemMessageEntity.builder().text("system").build(),
                UserMessageEntity.from("question"),
                AiMessageEntity.builder().text("a plain answer").build());
        pruner(GENEROUS_RATIO).compact(new ContextCompactRequest(kept, null));
        assertTrue(textsOf(kept).contains("a plain answer"), "SKELETON keeps the plain-text segment");
    }

    /** Case 18: an incomplete tool group is frozen and kept verbatim, thinking included. */
    @Test
    void prunePreservesIncompleteToolCallGroups() {
        AiMessageEntity working = AiMessageEntity.builder().text("calling the tool")
                .thinking("reason")
                .attributes(Map.of("thinking_signature", "signed"))
                .toolCalls(List.of(new ToolCallRequest("c1", "read", 0, "{}"))).build();
        Execution execution = execution(
                SystemMessageEntity.builder().text("system").build(),
                UserMessageEntity.from("go"),
                working,
                UserMessageEntity.from("next"));

        pruner(TINY_RATIO).compact(new ContextCompactRequest(execution, null));

        assertEquals(4, execution.getMessages().size(), "a frozen group is never trimmed");
        AiMessageEntity kept = assertInstanceOf(AiMessageEntity.class, execution.getMessages().get(2));
        assertSame(working, kept);
        assertEquals("reason", kept.getThinking(), "an active group keeps its thinking");
        assertEquals("signed", kept.getAttributes().get("thinking_signature"));
    }

    /** Case 20: the placeholder carries the payload size and the tool's exit status. */
    @Test
    void prunePlaceholderCarriesSizeAndExitCode() {
        Execution execution = execution(
                SystemMessageEntity.builder().text("system").build(),
                ai(null, "c1"),
                tool("c1", "one\ntwo\nthree\n" + "x".repeat(3_000), 1),
                ai(null, "c2"),
                tool("c2", "y".repeat(3_000), 0),
                ai(null, "c3"),
                tool("c3", "z".repeat(3_000), null));

        assertTrue(pruner(0.02d).compact(new ContextCompactRequest(execution, null)));

        List<String> placeholders = execution.getMessages().stream()
                .filter(ToolMessageEntity.class::isInstance)
                .map(Message::text)
                .toList();
        assertEquals(3, placeholders.size());
        assertTrue(placeholders.get(0).contains("exit=1"), placeholders.get(0));
        assertTrue(placeholders.get(1).contains("exit=0"), placeholders.get(1));
        assertTrue(placeholders.get(2).contains("ERROR"),
                "a tool without an exit status reports the failure it cannot express: " + placeholders.get(2));
        for (String placeholder : placeholders) {
            assertTrue(placeholder.startsWith("[output omitted ·"), placeholder);
            assertTrue(placeholder.contains("KB"), "the payload size is reported: " + placeholder);
            assertFalse(placeholder.contains("OUTPUT_TRUNCATED"),
                    "the placeholder must not look like the acquisition-time truncation marker");
        }
        assertTrue(placeholders.get(0).contains("4 lines"), "the line count is reported: " + placeholders.get(0));
    }

    /** Case 21: an assistant message and its tool results are kept or dropped together. */
    @Test
    void pruneNeverSplitsAnAiAndItsToolMessages() {
        Execution complete = execution(
                SystemMessageEntity.builder().text("system").build(),
                UserMessageEntity.from("question"),
                AiMessageEntity.builder().toolCalls(List.of(
                        new ToolCallRequest("c1", "read", 0, "{}"),
                        new ToolCallRequest("c2", "read", 1, "{}"))).build(),
                tool("c1", "a".repeat(3_000), null),
                tool("c2", "b".repeat(3_000), null));
        assertTrue(pruner(0.02d).compact(new ContextCompactRequest(complete, null)));
        List<String> ids = complete.getMessages().stream()
                .filter(ToolMessageEntity.class::isInstance)
                .map(message -> String.valueOf(((ToolMessageEntity) message).getId()))
                .toList();
        assertEquals(List.of("c1", "c2"), ids, "a skeleton keeps every result of the assistant message");

        Execution dropped = execution(
                SystemMessageEntity.builder().text("system").build(),
                UserMessageEntity.from("question"),
                AiMessageEntity.builder().toolCalls(List.of(
                        new ToolCallRequest("c1", "read", 0, "{}"),
                        new ToolCallRequest("c2", "read", 1, "{}"))).build(),
                tool("c1", "a".repeat(3_000), null),
                tool("c2", "b".repeat(3_000), null));
        pruner(TINY_RATIO).compact(new ContextCompactRequest(dropped, null));
        assertEquals(2, dropped.getMessages().size(), "a dropped segment takes all of its results with it");
    }

    /** Case 22: the product keeps the original chronology — old skeletons before new fulls. */
    @Test
    void pruneProductKeepsOriginalChronologicalOrder() {
        Execution execution = execution(
                SystemMessageEntity.builder().text("system").build(),
                UserMessageEntity.from("question"),
                AiMessageEntity.builder().text("older turn")
                        .toolCalls(List.of(new ToolCallRequest("c1", "read", 0, "{}"))).build(),
                tool("c1", "x".repeat(3_000), null),
                AiMessageEntity.builder().text("newer turn")
                        .toolCalls(List.of(new ToolCallRequest("c2", "read", 0, "{}"))).build(),
                tool("c2", "y".repeat(300), null));

        assertTrue(pruner(0.025d).compact(new ContextCompactRequest(execution, null)));

        List<String> texts = textsOf(execution);
        int older = texts.indexOf("older turn");
        int newer = texts.indexOf("newer turn");
        assertTrue(older >= 0 && newer >= 0, "both segments survive: " + texts);
        assertTrue(older < newer, "the older skeleton must stay ahead of the newer full segment: " + texts);
        assertTrue(texts.get(older + 1).startsWith("[output omitted ·"),
                "the skeleton's placeholder stays with its assistant message: " + texts);
        assertTrue(texts.get(newer + 1).equals("y".repeat(300)),
                "the newest segment keeps its payload, and keeps it after its own message: " + texts);
    }

    /** Case 23: a second pass over an already pruned context changes nothing. */
    @Test
    void pruneIsIdempotent() {
        Execution execution = execution(
                SystemMessageEntity.builder().text("system").build(),
                UserMessageEntity.from("question"),
                AiMessageEntity.builder().text("older turn")
                        .toolCalls(List.of(new ToolCallRequest("c1", "read", 0, "{}"))).build(),
                tool("c1", "x".repeat(3_000), null),
                AiMessageEntity.builder().text("newer turn")
                        .toolCalls(List.of(new ToolCallRequest("c2", "read", 0, "{}"))).build(),
                tool("c2", "y".repeat(300), null));
        DefaultContextPruner pruner = pruner(0.025d);

        assertTrue(pruner.compact(new ContextCompactRequest(execution, null)));
        List<String> firstProduct = textsOf(execution);
        List<Message> firstInstances = new ArrayList<>(execution.getMessages());

        assertFalse(pruner.compact(new ContextCompactRequest(execution, null)),
                "a second pass over the same generation must report no change");
        assertEquals(firstProduct, textsOf(execution), "the product is a fixed point");
        // Content equality alone would let a pass that rewrites equal messages pass as "no change";
        // a fixed point must not churn the instances either, otherwise every checkpoint would
        // replace the whole session with freshly built objects.
        for (int index = 0; index < firstInstances.size(); index++) {
            assertSame(firstInstances.get(index), execution.getMessages().get(index),
                    "message " + index + " must be the very same instance after an idempotent pass");
        }
    }

    /** Case 24: historical segments lose thinking, frozen ones keep it. */
    @Test
    void pruneRejectsThinkingBlocks() {
        AiMessageEntity historical = AiMessageEntity.builder().text("older turn")
                .thinking("secret")
                .attributes(Map.of("thinking_signature", "signed"))
                .toolCalls(List.of(new ToolCallRequest("c1", "read", 0, "{}"))).build();
        AiMessageEntity active = AiMessageEntity.builder().text("in flight")
                .thinking("still thinking")
                .toolCalls(List.of(new ToolCallRequest("c2", "read", 0, "{}"))).build();
        Execution execution = execution(
                SystemMessageEntity.builder().text("system").build(),
                UserMessageEntity.from("question"),
                historical,
                tool("c1", "x".repeat(3_000), null),
                active,
                UserMessageEntity.from("next"));

        assertTrue(pruner(0.025d).compact(new ContextCompactRequest(execution, null)));

        AiMessageEntity rewritten = assertInstanceOf(AiMessageEntity.class, execution.getMessages().get(2));
        assertNull(rewritten.getThinking(), "a rewritten historical message carries no thinking");
        assertTrue(rewritten.getAttributes().isEmpty(), "and no orphaned thinking signature");
        AiMessageEntity frozen = assertInstanceOf(AiMessageEntity.class, execution.getMessages().get(4));
        assertSame(active, frozen);
        assertEquals("still thinking", frozen.getThinking(),
                "an active group is kept verbatim, thinking included");
    }

    /** Case 26: a failing prune is reported and leaves the context usable. */
    @Test
    void pruneFailureDoesNotAbortTheLoop() {
        Tokenizer failing = new Tokenizer() {
            /** Only the token counting breaks; telemetry must keep working so the pair can be seen. */
            @Override
            public int count(List<Message> messages) {
                throw new IllegalStateException("tokenizer exploded");
            }

            @Override
            public String truncate(String output, int maxOutput) {
                return output;
            }

            @Override
            public double calcCurrentTokenRatio(List<Message> messages, int maxTokens) {
                return 0.95d;
            }

            @Override
            public ContextUsageMetric usage(List<Message> messages, Integer maxTokens) {
                return ContextUsageMetric.of(0, maxTokens == null ? 0 : maxTokens);
            }
        };
        DefaultContextPruner pruner = new DefaultContextPruner(
                AgentConfig.builder().maxTokens(MAX_TOKENS).pruneTargetRatio(0.40d).build(),
                reporter(failing));
        Execution execution = execution(
                SystemMessageEntity.builder().text("system").build(),
                UserMessageEntity.from("question"),
                ai(null, "c1"),
                tool("c1", "x".repeat(3_000), null));
        List<Message> before = new ArrayList<>(execution.getMessages());

        assertFalse(pruner.compact(new ContextCompactRequest(execution, null)),
                "a failing prune reports that it changed nothing");

        assertEquals(before, execution.getMessages(), "the context is left exactly as it was");
        assertEquals(0, execution.getCompactionState().pruneGeneration(),
                "a failed pass must not consume the generation's single prune");
        assertEquals(2, events.size(), "the squeeze pair is still closed");
        assertEquals(ContextUpdateEvent.Phase.SQUEEZE_COMPLETED, events.getLast().getPhase());
        assertEquals("Context prune failed", events.getLast().getMessage());
    }

    /** Case 31: a FULL segment caps every single tool result. */
    @Test
    void pruneRespectsToolOutputCapInFullSegments() {
        String payload = "x".repeat(90_000);
        Execution execution = execution(
                SystemMessageEntity.builder().text("system").build(),
                UserMessageEntity.from("question"),
                ai(null, "c1"),
                tool("c1", payload, null));
        AgentConfig config = AgentConfig.builder().maxTokens(MAX_TOKENS)
                .pruneTargetRatio(GENEROUS_RATIO).fullToolOutputCap(100).build();

        pruner(config).compact(new ContextCompactRequest(execution, null));

        ToolMessageEntity kept = assertInstanceOf(ToolMessageEntity.class, execution.getMessages().get(3));
        assertTrue(kept.text().length() < payload.length(), "the payload was capped");
        assertTrue(payload.startsWith(kept.text()), "the cap keeps the head of the output");
        assertTrue(tokenizer.count(kept) <= 100,
                "a FULL segment keeps at most the configured token cap, was " + tokenizer.count(kept));
    }

    // ------------------------------------------------------------------ fixtures

    private DefaultContextPruner pruner(double pruneTargetRatio) {
        return pruner(AgentConfig.builder().maxTokens(MAX_TOKENS)
                .pruneTargetRatio(pruneTargetRatio).build());
    }

    private DefaultContextPruner pruner(AgentConfig config) {
        return new DefaultContextPruner(config, reporter(tokenizer));
    }

    private ContextUsageReporter reporter(Tokenizer usageTokenizer) {
        return new ContextUsageReporter(usageTokenizer, MAX_TOKENS,
                new RuntimeEventPublisher(List.of(new RuntimeListener() {
                    @Override
                    public void onContextUpdate(ContextUpdateEvent event) {
                        events.add(event);
                    }
                })), 1);
    }

    private static AiMessageEntity ai(String text, String... callIds) {
        List<ToolCallRequest> calls = new ArrayList<>();
        for (int index = 0; index < callIds.length; index++) {
            calls.add(new ToolCallRequest(callIds[index], "read", index, "{}"));
        }
        return AiMessageEntity.builder().text(text).toolCalls(calls).build();
    }

    private static ToolMessageEntity tool(String id, String output, Integer exitCode) {
        return ToolMessageEntity.builder().id(id).name("read").toolOutput(output).exitCode(exitCode).build();
    }

    private static Execution execution(Message... messages) {
        List<Message> list = new ArrayList<>(List.of(messages));
        return Execution.builder().id("execution-1")
                .agentRequest(AgentRequest.builder().messages(list).build())
                .messages(list).build();
    }

    private static List<String> textsOf(Execution execution) {
        return execution.getMessages().stream().map(Message::text).toList();
    }
}
