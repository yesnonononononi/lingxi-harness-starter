package com.summit.core.conversation;

import com.summit.core.conversation.api.ToolCallRequest;
import com.summit.core.conversation.message.AiMessageEntity;
import com.summit.core.conversation.message.Message;
import com.summit.core.conversation.message.SystemMessageEntity;
import com.summit.core.conversation.message.ToolMessageEntity;
import com.summit.core.conversation.message.UserMessageEntity;
import com.summit.core.utils.ClassifyMessageUtil;
import com.summit.core.utils.ClassifyMessageUtil.AiSegment;
import com.summit.core.utils.ClassifyMessageUtil.Segment;
import org.junit.jupiter.api.Test;

import java.io.Serializable;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

/** Uses the public API from another package so inaccessible result types cannot pass compilation. */
class ClassifyMessageUtilTest {
    @Test
    void separatesUserTurnsAndCountsEveryExportIncludingTheFinalTurn() {
        UserMessageEntity first = UserMessageEntity.from("first");
        UserMessageEntity second = UserMessageEntity.from("second");
        UserMessageEntity third = UserMessageEntity.from("third");
        AiMessageEntity reply = ai("reply");
        AiMessageEntity pending = ai("pending", call("pending"));
        List<List<Message>> counted = new ArrayList<>();
        List<Segment> segments = ClassifyMessageUtil.normalizeSegments(
                List.of(first, reply, second, third, pending), messages -> {
                    counted.add(messages);
                    return counted.size() * 10;
                });

        assertEquals(3, segments.size());
        assertSame(first, segments.getFirst().getUserMessageEntity());
        assertEquals(List.of(first, reply), segments.getFirst().export());
        assertEquals(List.of(second), segments.get(1).export());
        assertEquals(List.of(third, pending), segments.getLast().export());
        assertEquals(List.of(10, 20, 30), segments.stream().map(Segment::getTokenCounts).toList());
        assertEquals(segments.stream().map(Segment::export).toList(), counted);
        assertTrue(segments.get(1).getAiSegments().isEmpty());
        assertTrue(segments.getLast().getAiSegments().getFirst().getToolMessageEntities().isEmpty());
    }

    @Test
    void matchesParallelAndDelayedResultsByCallIdAndPreservesOriginalExportOrder() {
        UserMessageEntity user = UserMessageEntity.from("question");
        AiMessageEntity first = ai("first", call("a"), call("c"));
        AiMessageEntity second = ai("second", call("b"));
        ToolMessageEntity c = tool("c");
        ToolMessageEntity b = tool("b");
        ToolMessageEntity a = tool("a");
        List<Message> input = List.of(user, first, second, c, b, a);

        Segment segment = ClassifyMessageUtil.normalizeSegments(input, messages -> 123).getFirst();

        List<AiSegment> groups = segment.getAiSegments();
        assertSame(first, groups.getFirst().getAiMessageEntity());
        assertEquals(List.of(c, a), groups.getFirst().getToolMessageEntities());
        assertEquals(List.of(b), groups.getLast().getToolMessageEntities());
        assertEquals(input, segment.export());
        assertEquals(123, segment.getTokenCounts());
    }

    @Test
    void normalizesNumericResultIdsWithoutCastingThemToStrings() {
        ToolMessageEntity result = tool(42L);
        Segment segment = ClassifyMessageUtil.normalizeSegments(
                List.of(UserMessageEntity.from("question"), ai("call", call("42")), result),
                messages -> 0).getFirst();

        assertSame(result, segment.getAiSegments().getFirst().getToolMessageEntities().getFirst());
        assertEquals(0, segment.getTokenCounts());
    }

    @Test
    void ignoresSystemNullLeadingAndOrphanMessagesWithoutCountingThem() {
        UserMessageEntity user = UserMessageEntity.from("question");
        AiMessageEntity call = ai("call", call("ok"));
        ToolMessageEntity result = tool("ok");
        List<Message> input = Arrays.asList(system(), null, ai("leading"), tool("leading"),
                user, tool("ok"), call, system(), tool("missing"), tool(null), result);

        Segment segment = ClassifyMessageUtil.normalizeSegments(input, messages -> {
            assertEquals(List.of(user, call, result), messages);
            return 7;
        }).getFirst();

        assertEquals(List.of(user, call, result), segment.export());
        assertEquals(7, segment.getTokenCounts());
    }

    @Test
    void doesNotAttachResultsAcrossUserTurnsAndAllowsTurnLocalIdsToBeReused() {
        UserMessageEntity first = UserMessageEntity.from("first");
        UserMessageEntity second = UserMessageEntity.from("second");
        AiMessageEntity firstAi = ai("first call", call("shared"));
        AiMessageEntity secondAi = ai("second call", call("shared"));
        ToolMessageEntity firstResult = tool("shared");
        ToolMessageEntity secondResult = tool("shared");
        List<Segment> segments = ClassifyMessageUtil.normalizeSegments(
                List.of(first, firstAi, firstResult, second, tool("shared"), secondAi, secondResult),
                messages -> 1);

        assertEquals(List.of(first, firstAi, firstResult), segments.getFirst().export());
        assertEquals(List.of(second, secondAi, secondResult), segments.getLast().export());
        assertSame(secondResult, segments.getLast().getAiSegments().getFirst().getToolMessageEntities().getFirst());
    }

    @Test
    void missingCallMetadataDoesNotCrashOrCreateFalseResultMatches() {
        AiMessageEntity malformed = AiMessageEntity.builder().text("pending")
                .toolCalls(Arrays.asList(null, call(null), call(" "))).build();
        AiMessageEntity noCalls = AiMessageEntity.builder().text("answer").build();
        UserMessageEntity user = UserMessageEntity.from("question");

        Segment segment = ClassifyMessageUtil.normalizeSegments(
                List.of(user, malformed, tool(" "), tool(null), noCalls), messages -> 1).getFirst();

        assertEquals(List.of(user, malformed, noCalls), segment.export());
        assertEquals(2, segment.getAiSegments().size());
        assertTrue(segment.getAiSegments().stream().allMatch(group -> group.getToolMessageEntities().isEmpty()));
    }

    @Test
    void rejectsAmbiguousDuplicateCallsAndDuplicateResults() {
        UserMessageEntity user = UserMessageEntity.from("question");
        assertThrows(IllegalArgumentException.class, () -> ClassifyMessageUtil.normalizeSegments(
                List.of(user, ai("one", call("id")), ai("two", call("id"))), messages -> 1));
        assertThrows(IllegalArgumentException.class, () -> ClassifyMessageUtil.normalizeSegments(
                List.of(user, ai("call", call("id"), call("id"))), messages -> 1));
        assertThrows(IllegalArgumentException.class, () -> ClassifyMessageUtil.normalizeSegments(
                List.of(user, ai("call", call("id")), tool("id"), tool("id")), messages -> 1));
    }

    @Test
    void emptyOrUnclassifiableInputNeverCallsTheCounter() {
        AtomicInteger calls = new AtomicInteger();
        for (List<Message> input : Arrays.asList(null, List.<Message>of(),
                Arrays.asList(null, system(), ai("leading"), tool("orphan")))) {
            assertTrue(ClassifyMessageUtil.normalizeSegments(input, messages -> calls.incrementAndGet()).isEmpty());
        }
        assertEquals(0, calls.get());
        assertThrows(NullPointerException.class, () -> ClassifyMessageUtil.normalizeSegments(List.of(), null));
    }

    @Test
    void rejectsMissingOrNegativeTokenCountsAndPropagatesCounterFailures() {
        List<Message> messages = List.of(UserMessageEntity.from("question"));
        assertThrows(IllegalArgumentException.class, () -> ClassifyMessageUtil.normalizeSegments(messages, ignored -> null));
        assertThrows(IllegalArgumentException.class, () -> ClassifyMessageUtil.normalizeSegments(messages, ignored -> -1));
        RuntimeException failure = new IllegalStateException("tokenizer failed");
        assertSame(failure, assertThrows(IllegalStateException.class,
                () -> ClassifyMessageUtil.normalizeSegments(messages, ignored -> { throw failure; })));
    }

    @Test
    void exposesReadOnlyContainersAndLeavesTheInputUnchanged() {
        UserMessageEntity user = UserMessageEntity.from("question");
        AiMessageEntity ai = ai("call", call("id"));
        ToolMessageEntity tool = tool("id");
        List<Message> input = new ArrayList<>(List.of(user, ai, tool));
        List<Segment> segments = ClassifyMessageUtil.normalizeSegments(input, messages -> {
            assertThrows(UnsupportedOperationException.class, messages::clear);
            return 1;
        });

        assertEquals(List.of(user, ai, tool), input);
        assertThrows(UnsupportedOperationException.class, segments::clear);
        assertThrows(UnsupportedOperationException.class, segments.getFirst().getAiSegments()::clear);
        assertThrows(UnsupportedOperationException.class, segments.getFirst().getAiSegments().getFirst().getToolMessageEntities()::clear);
        assertThrows(UnsupportedOperationException.class, segments.getFirst().export()::clear);
        input.clear();
        assertEquals(List.of(user, ai, tool), segments.getFirst().export());
    }

    private static AiMessageEntity ai(String text, ToolCallRequest... calls) {
        return AiMessageEntity.builder().text(text).toolCalls(List.of(calls)).build();
    }

    private static ToolCallRequest call(String id) {
        return new ToolCallRequest(id, "read", 0, "{}");
    }

    private static ToolMessageEntity tool(Serializable id) {
        return ToolMessageEntity.builder().id(id).name("read").toolOutput("output").build();
    }

    private static SystemMessageEntity system() {
        return SystemMessageEntity.builder().text("system").build();
    }
}
