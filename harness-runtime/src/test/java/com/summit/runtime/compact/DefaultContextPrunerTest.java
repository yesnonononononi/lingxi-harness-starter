package com.summit.runtime.compact;

import com.summit.core.conversation.message.TokenUsageEntity;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.summit.core.agent.AgentRequest;
import com.summit.core.agent.ContextSpec;
import com.summit.core.agent.CompactionState;
import com.summit.core.agent.Execution;
import com.summit.core.compact.ContextAttachmentProvider;
import com.summit.core.compact.ContextCompactRequest;
import com.summit.core.conversation.api.ChatResponseEntity;
import com.summit.core.conversation.api.ToolCallRequest;
import com.summit.core.conversation.event.ContextUpdateEvent;
import com.summit.core.conversation.event.RuntimeEventPublisher;
import com.summit.core.conversation.message.AiMessageEntity;
import com.summit.core.conversation.message.Message;
import com.summit.core.conversation.message.SystemMessageEntity;
import com.summit.core.conversation.message.ToolMessageEntity;
import com.summit.core.conversation.message.UserMessageEntity;
import com.summit.core.exception.TokenBudgetExceededException;
import com.summit.core.json.ExecutionJson;
import com.summit.core.runtime.RuntimeListener;
import com.summit.core.runtime.loop.ContextUsageReporter;
import com.summit.core.runtime.loop.ExecutionControlSignal;
import com.summit.runtime.agent.AgentConfig;
import com.summit.runtime.context.RuntimeContext;
import com.summit.runtime.conversation.DefaultConversationManager;
import com.summit.runtime.conversation.DefaultTokenizer;
import com.summit.runtime.loop.BoundaryChecker;
import com.summit.runtime.loop.AgentLoopStepRunner;
import com.summit.runtime.loop.DefaultLoopInterceptorProcessor;
import com.summit.runtime.model.DefaultChatModelInvoker;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

/** The fallback preserves user turns, assistant findings, and the latest complete user turn. */
class DefaultContextPrunerTest {

    private final DefaultTokenizer tokenizer = new DefaultTokenizer();
    private final List<ContextUpdateEvent> events = new ArrayList<>();

    @Test
    void pruningKeepsPendingInputUntilAReplyAndPreservesTheRealUserTurn() {
        UserMessageEntity notification = user("new receipt").markPendingCompaction();
        Execution execution = execution(system("system"), user("question"),
                AiMessageEntity.builder().text("earlier reply").toolCalls(List.of(call("lookup", "{}"))).build(),
                tool("lookup", "result", false, 0), notification);
        DefaultContextPruner pruner = pruner(2_000);
        ContextCompactRequest request = new ContextCompactRequest(execution);

        pruner.compact(request);
        pruner.compact(request);

        assertSame(notification, execution.getMessages().getLast());
        assertTrue(hasTool(execution, "lookup"));
        assertEquals(false, notification.getAttrs().get(UserMessageEntity.COMPACT));

        execution.getMessages().add(AiMessageEntity.builder().text("receipt read").build());
        assertTrue(pruner.compact(request));
        assertEquals(true, ((UserMessageEntity) execution.getMessages().get(4)).getAttrs().get(UserMessageEntity.COMPACT));
        assertEquals(false, notification.getAttrs().get(UserMessageEntity.COMPACT));
        assertTrue(pruner.compact(request));
        assertEquals(List.of("question"), execution.getMessages().stream()
                .filter(UserMessageEntity.class::isInstance).map(Message::text).toList());
    }

    @Test
    void keepsEveryAssistantTextAndDropsHistoricalToolArgumentsAndResults() {
        Execution execution = history("{}", "old payload".repeat(2_000), "latest payload".repeat(50));
        List<Message> original = new ArrayList<>(execution.getMessages());

        assertTrue(pruner(2_000).compact(new ContextCompactRequest(execution)));

        assertEquals(List.of("earlier finding", "latest reasoning"), assistantTexts(execution));
        assertEquals(1, execution.getCompactionState().pruneGeneration());
        assertEquals("{\"path\":\"file\"}", lastAi(execution).getToolCalls().getFirst().arguments());
        assertSame(original.getLast(), execution.getMessages().getLast());
        assertTrue(((AiMessageEntity) execution.getMessages().get(2)).getToolCalls().isEmpty());
        assertFalse(hasTool(execution, "old"));
        assertEquals(List.of("question", "follow-up"), execution.getMessages().stream()
                .filter(UserMessageEntity.class::isInstance).map(Message::text).toList());
    }

    @Test
    void largeHistoricalArgumentsAreRemovedWithoutReducingTheLatestTurn() {
        Execution execution = history("x".repeat(10_000), "old output", "latest output");

        assertTrue(pruner(1_000).compact(new ContextCompactRequest(execution)));

        assertEquals(1, execution.getCompactionState().pruneGeneration());
        assertEquals(List.of("earlier finding", "latest reasoning"), assistantTexts(execution));
        AiMessageEntity earlier = (AiMessageEntity) execution.getMessages().get(2);
        assertTrue(earlier.getToolCalls() == null || earlier.getToolCalls().isEmpty());
        assertEquals(List.of("latest"), execution.getMessages().stream()
                .filter(ToolMessageEntity.class::isInstance)
                .map(ToolMessageEntity.class::cast).map(tool -> String.valueOf(tool.getId())).toList());
        assertEquals("latest output", findTool(execution, "latest").text());
    }

    @Test
    void removesHistoricalAssistantWithoutTextWhenItsCallsAreDropped() {
        Execution execution = history("x".repeat(10_000), "old output", "latest output");
        ((AiMessageEntity) execution.getMessages().get(2)).setText(null);

        pruner(1_000).compact(new ContextCompactRequest(execution));

        assertEquals(List.of("latest reasoning"), assistantTexts(execution));
        assertEquals(5, execution.getMessages().size());
    }

    @Test
    void preservesEveryResultOfTheLatestParallelCallWithoutAnOutputCap() {
        ToolMessageEntity first = tool("a", "a".repeat(15_000), false, 0);
        ToolMessageEntity second = tool("b", "b".repeat(15_000), false, 0);
        Execution execution = execution(system("system"), user("question"),
                AiMessageEntity.builder().text("parallel work").toolCalls(List.of(
                        call("a", "{}"), call("b", "{}"))).build(), first, second);

        assertFalse(pruner(20_000).compact(new ContextCompactRequest(execution)));

        assertSame(first, findTool(execution, "a"));
        assertSame(second, findTool(execution, "b"));
        assertEquals(2, lastAi(execution).getToolCalls().size());
    }

    @Test
    void cannotDiscardTheLatestLargeOutputToMakeTheContextFit() {
        Execution execution = history("{}", "old output", "latest output".repeat(2_000));
        List<Message> original = execution.getMessages();

        TokenBudgetExceededException error = assertThrows(TokenBudgetExceededException.class,
                () -> pruner(1_000).compact(new ContextCompactRequest(execution)));

        assertTrue(error.getMessage().contains("Increase the context budget"));
        assertTrue(error.getMessage().contains("Protected messages"));
        assertTrue(error.getMessage().chars().allMatch(character -> character < 128));
        assertSame(original, execution.getMessages());
        assertEquals(0, execution.getCompactionState().pruneGeneration());
        assertPairedEvents();
    }

    @Test
    void protectedMessagesAndAssistantTextOverflowFailsAtomically() {
        Execution execution = execution(system("s".repeat(4_000)), user("u".repeat(4_000)),
                AiMessageEntity.builder().text("older discovery".repeat(500)).build(),
                AiMessageEntity.builder().text("latest answer").build());
        List<Message> original = execution.getMessages();

        assertThrows(TokenBudgetExceededException.class,
                () -> pruner(1_000).compact(new ContextCompactRequest(execution)));

        assertSame(original, execution.getMessages());
        assertEquals(0, execution.getCompactionState().pruneGeneration());
        assertPairedEvents();
    }

    @Test
    void keepsSystemAndUserObjectsAndChronologyWhileRemovingHistoricalPairs() {
        SystemMessageEntity leading = system("leading");
        SystemMessageEntity attachment = system("business state");
        UserMessageEntity first = user("first");
        UserMessageEntity interleaved = user("injected input");
        Execution execution = execution(leading, first,
                AiMessageEntity.builder().text("earlier finding")
                        .toolCalls(List.of(call("a", "{}"), call("b", "{}"))).build(),
                tool("a", "a".repeat(9_000), false, 0), interleaved,
                tool("b", "b".repeat(9_000), false, 0), attachment,
                AiMessageEntity.builder().text("latest answer").build());

        pruner(2_000).compact(new ContextCompactRequest(execution));

        List<Message> messages = execution.getMessages();
        assertSame(leading, messages.get(0));
        assertSame(attachment, messages.get(1));
        assertSame(first, messages.get(2));
        assertSame(interleaved, messages.get(4));
        assertEquals(List.of("earlier finding", "latest answer"), assistantTexts(execution));
        assertTrue(messages.stream().noneMatch(ToolMessageEntity.class::isInstance));
        assertEquals(1, execution.getCompactionState().pruneGeneration());
    }

    @Test
    void historicalProjectionIsIdempotentRegardlessOfArgumentSize() {
        for (String arguments : List.of("{}", "x".repeat(10_000))) {
            events.clear();
            Execution execution = history(arguments, "old output".repeat(1_000), "latest output");
            DefaultContextPruner pruner = pruner(1_000);
            assertTrue(pruner.compact(new ContextCompactRequest(execution)));
            List<Message> first = new ArrayList<>(execution.getMessages());
            int stage = execution.getCompactionState().pruneGeneration();

            assertFalse(pruner.compact(new ContextCompactRequest(execution)));

            assertEquals(first, execution.getMessages());
            assertEquals(stage, execution.getCompactionState().pruneGeneration());
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {"[output omitted: example]\n", "[output omitted: ", "[output omitted: 1B / exit=0]"})
    void historicalOutputsAreDroppedRegardlessOfTheirSpelling(String prefix) {
        String payload = prefix + "9".repeat(30_000) + "]";
        Execution execution = history("{}", payload, "latest output");

        pruner(1_000).compact(new ContextCompactRequest(execution));

        assertFalse(hasTool(execution, "old"));
        assertEquals("latest output", findTool(execution, "latest").text());
    }

    @Test
    void removesStaleThinkingWithoutChangingSourceSnapshotOrToolArguments() {
        Execution execution = history("{\"key\":42}", "old output", "latest output");
        AiMessageEntity original = lastAi(execution);
        original.setThinking("signed reasoning");
        original.setAttributes(Map.of("thinking_signature", "signature", "redacted_thinking", "encrypted"));

        pruner(2_000).compact(new ContextCompactRequest(execution));

        AiMessageEntity rewritten = lastAi(execution);
        assertNull(rewritten.getThinking());
        assertTrue(rewritten.getAttributes().isEmpty());
        assertEquals(original.getText(), rewritten.getText());
        assertEquals(original.getToolCalls(), rewritten.getToolCalls());
        assertEquals("signed reasoning", original.getThinking());
        assertEquals("signature", original.getAttributes().get("thinking_signature"));
    }

    @Test
    void incompleteGroupDefersTheWholeRewriteWithoutInvalidatingItsSignedPrefix() {
        Execution execution = history("{}", "old output".repeat(2_000), "latest output");
        execution.getMessages().removeLast();
        lastAi(execution).setThinking("pending reasoning");
        lastAi(execution).setAttributes(Map.of("thinking_signature", "signed"));
        List<Message> original = execution.getMessages();

        assertFalse(pruner(20_000).compact(new ContextCompactRequest(execution)));

        assertSame(original, execution.getMessages());
        assertEquals("pending reasoning", lastAi(execution).getThinking());
        assertEquals(0, execution.getCompactionState().pruneGeneration());
        assertPairedEvents();
    }

    @Test
    void oversizedIncompleteGroupFailsWithoutChangingItsPendingCall() {
        Execution execution = execution(system("system"), user("question"),
                AiMessageEntity.builder().text("x".repeat(6_000))
                        .toolCalls(List.of(call("pending", "{}"))).build());
        List<Message> original = execution.getMessages();

        assertThrows(TokenBudgetExceededException.class,
                () -> pruner(1_000).compact(new ContextCompactRequest(execution)));

        assertSame(original, execution.getMessages());
        assertPairedEvents();
    }

    @Test
    void checkpointPrunesAgainAsNewWorkingGroupsAccumulate() {
        AgentConfig config = AgentConfig.builder().maxTokens(2_000).build();
        DefaultContextPruner pruner = new DefaultContextPruner(config, reporter(2_000));
        BoundaryChecker checker = new BoundaryChecker(config, tokenizer,
                new DefaultConversationManager(id -> Optional.empty()), pruner, null, null, null);
        Execution execution = history("{}", "old output".repeat(2_000), "tiny latest result");

        checker.before(TokenUsageEntity.empty(), execution);
        assertEquals(1, execution.getCompactionState().pruneGeneration());
        findTool(execution, "latest").setToolOutput("large former latest result".repeat(1_000));
        execution.getMessages().add(AiMessageEntity.builder().text("next finding")
                .toolCalls(List.of(call("next", "{}"))).build());
        execution.getMessages().add(tool("next", "new latest", false, 0));

        checker.before(TokenUsageEntity.empty(), execution);

        assertTrue(tokenizer.count(execution.getMessages()) <= 2_000);
        assertFalse(hasTool(execution, "latest"));
        assertEquals("new latest", findTool(execution, "next").text());
        assertEquals(2, execution.getCompactionState().pruneGeneration());
        assertTrue(execution.getCompactionState().summaryUnavailable());
    }

    @Test
    void terminalOverflowEscapesTheSummaryFailureFallback() {
        AtomicInteger modelCalls = new AtomicInteger();
        AgentConfig config = AgentConfig.builder().maxTokens(1_000).build();
        DefaultConversationManager conversations = new DefaultConversationManager(id -> Optional.empty());
        ContextUsageReporter usage = reporter(1_000);
        DefaultModelCompacter compacter = DefaultModelCompacter.builder().models(ignored -> request -> {
            modelCalls.incrementAndGet();
            throw new IllegalStateException("offline model failure");
        }).conversationManager(conversations).contextAttachmentProvider(ContextAttachmentProvider.NONE)
                .usage(usage).summaryApplier(new DefaultCompactSummaryApplier(conversations)).build();
        BoundaryChecker checker = new BoundaryChecker(config, tokenizer, conversations,
                new DefaultContextPruner(config, usage), compacter, null, null);
        Execution execution = history("{}", "old output", "latest output".repeat(1_000));

        assertThrows(TokenBudgetExceededException.class, () -> checker.before(TokenUsageEntity.empty(), execution));

        assertEquals(1, modelCalls.get());
        assertTrue(execution.getCompactionState().summaryUnavailable());
        assertEquals(0, execution.getCompactionState().pruneGeneration());
    }

    @Test
    void businessAttachmentIsProtectedAndDoesNotDuplicateOnAnotherPass() {
        Execution execution = history("x".repeat(10_000), "old output", "latest output");
        ContextAttachmentProvider attachments = id -> Optional.of("authoritative business plan");
        DefaultContextPruner pruner = new DefaultContextPruner(
                AgentConfig.builder().maxTokens(1_000).build(), reporter(1_000), attachments);

        assertTrue(pruner.compact(new ContextCompactRequest(execution)));
        assertFalse(pruner.compact(new ContextCompactRequest(execution)));

        assertEquals(1, execution.getCompactionState().pruneGeneration());
        assertEquals(1, execution.getMessages().stream()
                .filter(SystemMessageEntity.class::isInstance)
                .filter(message -> message.text().contains("authoritative business plan")).count());
    }

    @Test
    void businessAttachmentIsIncludedInTheTerminalBudgetCheck() {
        Execution execution = history("{}", "old output", "latest output");
        List<Message> original = execution.getMessages();
        DefaultContextPruner pruner = new DefaultContextPruner(
                AgentConfig.builder().maxTokens(1_000).build(), reporter(1_000),
                id -> Optional.of("business plan".repeat(1_000)));

        assertThrows(TokenBudgetExceededException.class, () -> pruner.compact(new ContextCompactRequest(execution)));

        assertSame(original, execution.getMessages());
        assertEquals(0, execution.getCompactionState().pruneGeneration());
    }

    @Test
    void anotherSmallGroupInTheLatestTurnIsKeptInFull() {
        Execution execution = history("x".repeat(10_000), "old output", "latest output");
        DefaultContextPruner pruner = pruner(1_000);
        pruner.compact(new ContextCompactRequest(execution));

        execution.getMessages().add(AiMessageEntity.builder().text("new finding")
                .toolCalls(List.of(call("next", "{}"))).build());
        execution.getMessages().add(tool("next", "new output", false, 0));

        assertFalse(pruner.compact(new ContextCompactRequest(execution)));

        assertEquals(1, execution.getCompactionState().pruneGeneration());
        assertEquals(List.of("earlier finding", "latest reasoning", "new finding"), assistantTexts(execution));
        assertEquals(2, execution.getMessages().stream().filter(ToolMessageEntity.class::isInstance).count());
        assertEquals("new output", findTool(execution, "next").text());
    }

    @Test
    void orphanResultsAreRemovedWithoutDroppingAssistantFindings() {
        Execution execution = execution(system("system"), user("question"),
                tool("orphan", "unpaired", false, null), AiMessageEntity.builder().text("finding").build());

        assertTrue(pruner(1_000).compact(new ContextCompactRequest(execution)));

        assertEquals(List.of("finding"), assistantTexts(execution));
        assertEquals(3, execution.getMessages().size());
    }

    @Test
    void invalidToolPairFailsInsteadOfPublishingAPartialRewrite() {
        Execution execution = execution(system("system"), tool("late", "result", false, null),
                AiMessageEntity.builder().toolCalls(List.of(call("late", "{}"))).build());
        List<Message> original = execution.getMessages();

        assertThrows(IllegalStateException.class, () -> pruner(1_000).compact(new ContextCompactRequest(execution)));

        assertSame(original, execution.getMessages());
        assertPairedEvents();
    }

    @Test
    void emptyContextStillClosesTheEventPair() {
        Execution execution = execution();

        assertFalse(pruner(1_000).compact(new ContextCompactRequest(execution)));

        assertPairedEvents();
    }

    @Test
    void irreducibleOverflowStopsTheActualLoopBeforeInvokingTheMainModel() {
        AtomicInteger mainCalls = new AtomicInteger();
        AgentConfig config = AgentConfig.builder().maxTokens(1_000).build();
        DefaultConversationManager conversations = new DefaultConversationManager(ContextAttachmentProvider.NONE);
        BoundaryChecker checker = new BoundaryChecker(config, tokenizer, conversations,
                new DefaultContextPruner(config, reporter(1_000)), null, null, null);

        RuntimeContext context = RuntimeContext.builder()
                .conversationManager(conversations)
                .tokenizer(tokenizer).usage(reporter(1_000))
                .runtimeBoundaryChecker(checker)
                .loopInterceptorProcessor(new DefaultLoopInterceptorProcessor(List.of()))
                .invoker(new DefaultChatModelInvoker(request -> {
                    mainCalls.incrementAndGet();
                    throw new AssertionError("An oversized request reached the main model");
                }))
                .build();

        Execution execution = history("{}", "old output", "latest output".repeat(1_000));

        assertThrows(TokenBudgetExceededException.class, () -> new AgentLoopStepRunner(context)
                .run(execution, new ExecutionControlSignal(execution.getId())));

        assertEquals(0, mainCalls.get());
        assertEquals(0, execution.getModelAttempts());
    }

    @Test
    void snapshotRestorationKeepsTheCompleteLatestUserTurn() throws Exception {
        Execution execution = history("x".repeat(10_000), "old output", "latest output");
        DefaultContextPruner pruner = pruner(1_000);
        pruner.compact(new ContextCompactRequest(execution));

        ObjectMapper mapper = ExecutionJson.newObjectMapper();
        Execution restored = mapper.readValue(mapper.writeValueAsBytes(execution), Execution.class);
        restored.getMessages().add(AiMessageEntity.builder().text("after restart")
                .toolCalls(List.of(call("resumed", "{}"))).build());
        restored.getMessages().add(tool("resumed", "restored output", false, 0));

        pruner.compact(new ContextCompactRequest(restored));

        assertEquals(1, restored.getCompactionState().pruneGeneration());
        assertEquals(List.of("earlier finding", "latest reasoning", "after restart"), assistantTexts(restored));
        assertEquals(2, restored.getMessages().stream().filter(ToolMessageEntity.class::isInstance).count());
        assertEquals("restored output", findTool(restored, "resumed").text());
    }

    @Test
    void completeLatestTurnIsRetainedEvenWhenItOccupiesMostOfTheContextBelowThreshold() {
        Execution execution = history("{}", "old output", "x".repeat(1_800));
        AiMessageEntity another = AiMessageEntity.builder().text("next action")
                .toolCalls(List.of(call("next", "{\"pid\":42}"))).build();
        ToolMessageEntity result = tool("next", "complete result", false, 0);
        execution.getMessages().add(another);
        execution.getMessages().add(result);

        pruner(1_000).compact(new ContextCompactRequest(execution));

        assertEquals(1, execution.getCompactionState().pruneGeneration());
        assertEquals("x".repeat(1_800), findTool(execution, "latest").text());
        assertSame(another, lastAi(execution));
        assertSame(result, findTool(execution, "next"));
    }

    @Test
    void dominantLatestTurnKeepsAllAssistantTextAndTheLastCompleteParallelToolGroup() {
        String arguments = "{\"content\":\"" + "x".repeat(6_000) + "\"}";
        AiMessageEntity earlier = AiMessageEntity.builder().text("earlier action")
                .toolCalls(List.of(call("discarded", arguments))).build();
        AiMessageEntity latest = AiMessageEntity.builder().text("latest action")
                .toolCalls(List.of(call("a", "{\"path\":\"file\"}"), call("b", "{\"pid\":42}"))).build();
        ToolMessageEntity a = tool("a", "full file result", false, 0);
        ToolMessageEntity b = tool("b", "full process result", true, 7);
        Execution execution = execution(system("system"), user("question"), earlier, latest,
                b, tool("discarded", "delayed old result", false, 0), a);
        List<Message> original = new ArrayList<>(execution.getMessages());
        DefaultContextPruner pruner = pruner(1_000);

        assertTrue(pruner.compact(new ContextCompactRequest(execution)));

        assertEquals(List.of("earlier action", "latest action"), assistantTexts(execution));
        assertTrue(((AiMessageEntity) execution.getMessages().get(2)).getToolCalls().isEmpty());
        assertSame(latest, lastAi(execution));
        assertEquals(List.of(b, a), execution.getMessages().stream().filter(ToolMessageEntity.class::isInstance).toList());
        assertEquals(2, execution.getCompactionState().pruneGeneration());
        assertEquals(arguments, ((AiMessageEntity) original.get(2)).getToolCalls().getFirst().arguments());
        assertFalse(pruner.compact(new ContextCompactRequest(execution)));
        assertEquals(2, execution.getCompactionState().pruneGeneration());
    }

    @Test
    void latestPlainAnswerIsKeptWhileEarlierCallsInThatTurnAreRemoved() {
        Execution execution = execution(system("system"), user("question"),
                AiMessageEntity.builder().toolCalls(List.of(call("old", "x".repeat(6_000)))).build(),
                tool("old", "old result", false, 0), AiMessageEntity.builder().text("final finding").build());

        pruner(1_000).compact(new ContextCompactRequest(execution));

        assertEquals(List.of("final finding"), assistantTexts(execution));
        assertEquals(3, execution.getMessages().size());
        assertEquals(2, execution.getCompactionState().pruneGeneration());
    }

    @Test
    void reachingConfiguredThresholdTriggersLatestTurnReductionEvenBelowTheHardBudget() {
        Execution execution = execution(system("s".repeat(300)), user("question"),
                AiMessageEntity.builder().text("first action").toolCalls(List.of(call("old", "{}"))).build(),
                tool("old", "x".repeat(1_500), false, 0), AiMessageEntity.builder().text("latest finding").build());
        int tokens = tokenizer.count(execution.getMessages());
        AgentConfig config = AgentConfig.builder().maxTokens(1_000)
                .squeezeThreshold(new AgentConfig.ProgressiveSqueezePolicy(
                        new AgentConfig.ModelSqueeze(tokens / 1_000.0))).build();
        assertTrue(tokens < 850, "the configured threshold must differ from the default and hard budget");

        new DefaultContextPruner(config, reporter(1_000)).compact(new ContextCompactRequest(execution));

        assertFalse(hasTool(execution, "old"));
        assertEquals(List.of("first action", "latest finding"), assistantTexts(execution));
        assertEquals(2, execution.getCompactionState().pruneGeneration());
        assertTrue(tokenizer.count(execution.getMessages()) < tokens);
    }

    @ParameterizedTest
    @ValueSource(ints = {450, 451})
    void latestTurnAtOrBelowHalfCannotBeReducedDespiteReclaimableToolContent(int protectedTokens) {
        // Latest turn: 10 user tokens + 10 assistant tokens + 1 tool-name token + 429 output tokens = 450.
        Execution execution = execution(system("s".repeat(protectedTokens * 3)), user("u".repeat(30)),
                AiMessageEntity.builder().text("a".repeat(30)).toolCalls(List.of(call("old", "{}"))).build(),
                tool("old", "x".repeat(429 * 3), false, 0), AiMessageEntity.builder().text("").build());
        List<Message> original = execution.getMessages();

        TokenBudgetExceededException error = assertThrows(TokenBudgetExceededException.class,
                () -> pruner(1_000).compact(new ContextCompactRequest(execution)));

        assertTrue(error.getMessage().contains("does not occupy more than half"));
        assertSame(original, execution.getMessages());
        assertEquals(CompactionState.none(), execution.getCompactionState());
        assertPairedEvents();
    }

    @Test
    void overflowAfterReducingLatestTurnPreservesMessagesAndPersistedState() {
        Execution execution = execution(system("system"), user("question"),
                AiMessageEntity.builder().text("first action").toolCalls(List.of(call("old", "{}"))).build(),
                tool("old", "x".repeat(6_000), false, 0),
                AiMessageEntity.builder().text("latest action").toolCalls(List.of(call("latest", "{}"))).build(),
                tool("latest", "y".repeat(2_550), false, 0));
        CompactionState state = CompactionState.none().withSummaryGeneration(3).withPruneGeneration(1);
        execution.setCompactionState(state);
        List<Message> original = execution.getMessages();

        assertThrows(TokenBudgetExceededException.class, () -> pruner(1_000).compact(new ContextCompactRequest(execution)));

        assertSame(original, execution.getMessages());
        assertSame(state, execution.getCompactionState());
        assertEquals("x".repeat(6_000), findTool(execution, "old").text());
        assertPairedEvents();
    }

    @Test
    void priorSecondStageDoesNotForceRemovalOfToolsFromANewUserTurn() {
        Execution execution = history("{}", "old output", "latest output");
        execution.setCompactionState(CompactionState.none().withPruneGeneration(2));
        execution.getMessages().add(user("new user turn"));
        AiMessageEntity first = AiMessageEntity.builder().text("first action")
                .toolCalls(List.of(call("first", "{}"))).build();
        execution.getMessages().add(first);
        execution.getMessages().add(tool("first", "full first result", false, 0));
        execution.getMessages().add(AiMessageEntity.builder().text("second action")
                .toolCalls(List.of(call("second", "{}"))).build());
        execution.getMessages().add(tool("second", "full second result", false, 0));

        pruner(1_000).compact(new ContextCompactRequest(execution));

        assertFalse(hasTool(execution, "latest"));
        assertEquals("full first result", findTool(execution, "first").text());
        assertEquals("full second result", findTool(execution, "second").text());
        assertSame(first, execution.getMessages().get(6));
    }

    @Test
    void successfulSummariesStillOverTheHardBudgetCannotEscapeToTheMainModel() {
        AtomicInteger summaryCalls = new AtomicInteger();
        AgentConfig config = AgentConfig.builder().maxTokens(1_000).maxSummaryAttempts(2).build();
        DefaultConversationManager conversations = new DefaultConversationManager(ContextAttachmentProvider.NONE);
        ContextUsageReporter usage = reporter(1_000);
        DefaultModelCompacter summaries = DefaultModelCompacter.builder().models(ignored -> request -> {
            summaryCalls.incrementAndGet();
            return ChatResponseEntity.builder().aiMessageEntity(AiMessageEntity.builder()
                    .text("{\"summary\":\"usable summary\"}").build()).build();
        }).conversationManager(conversations).contextAttachmentProvider(ContextAttachmentProvider.NONE)
                .usage(usage).summaryApplier(new DefaultCompactSummaryApplier(conversations)).build();

        BoundaryChecker checker = new BoundaryChecker(config, tokenizer, conversations,
                new DefaultContextPruner(config, usage), summaries, null, null);
        Execution execution = execution(system("system"), user("protected input".repeat(1_000)),
                AiMessageEntity.builder().text("latest finding").build());

        assertThrows(TokenBudgetExceededException.class, () -> checker.before(TokenUsageEntity.empty(), execution));

        assertEquals(2, summaryCalls.get());
        assertEquals(2, execution.getCompactionState().summaryAttempts());
        assertEquals(2, execution.getCompactionState().summaryGeneration());
        assertTrue(execution.getCompactionState().summaryUnavailable());
    }

    @Test
    void pruneKeepsOnlyTheNewestDynamicContextBlock() {
        UserMessageEntity newest = UserMessageEntity.from("<context> NEWEST").markContextContract();
        Execution execution = execution(
                SystemMessageEntity.builder().text("LEADING").build(),
                UserMessageEntity.from("<context> OLDEST").markContextContract(),
                UserMessageEntity.from("first question"),
                AiMessageEntity.builder().text("first answer").build(),
                newest,
                UserMessageEntity.from("second question"));

        pruner(20_000).compact(new ContextCompactRequest(execution));

        List<UserMessageEntity> blocks = execution.getMessages().stream()
                .filter(UserMessageEntity.class::isInstance)
                .map(UserMessageEntity.class::cast)
                .filter(UserMessageEntity::hasContextContract)
                .toList();
        assertEquals(1, blocks.size(), "superseded background blocks are dropped: " + execution.getMessages());
        assertSame(newest, blocks.getFirst(),
                "the newest block survives: pruning must never drop the live background knowledge");
        assertTrue(execution.getMessages().stream().anyMatch(message -> "second question".equals(message.text())),
                "the current user turn is still there");
    }

    private DefaultContextPruner pruner(int budget) {
        return new DefaultContextPruner(AgentConfig.builder().maxTokens(budget).build(), reporter(budget));
    }

    private ContextUsageReporter reporter(int budget) {
        return new ContextUsageReporter(tokenizer, budget, new RuntimeEventPublisher(List.of(new RuntimeListener() {
            @Override
            public void onContextUpdate(ContextUpdateEvent event) {
                events.add(event);
            }
        })), 1);
    }

    private void assertPairedEvents() {
        assertEquals(2, events.size());
        assertEquals(ContextUpdateEvent.Phase.SQUEEZE_STARTED, events.getFirst().getPhase());
        assertEquals(ContextUpdateEvent.Phase.SQUEEZE_COMPLETED, events.getLast().getPhase());
        events.forEach(event -> assertEquals(ContextUpdateEvent.Band.PRUNE, event.getBand()));
    }

    static Execution history(String arguments, String oldOutput, String latestOutput) {
        return execution(system("system"), user("question"),
                AiMessageEntity.builder().text("earlier finding").toolCalls(List.of(call("old", arguments))).build(),
                tool("old", oldOutput, true, 7),
                user("follow-up"),
                AiMessageEntity.builder().text("latest reasoning")
                        .toolCalls(List.of(call("latest", "{\"path\":\"file\"}"))).build(),
                tool("latest", latestOutput, false, 0));
    }

    static Execution execution(Message... messages) {
        List<Message> copy = new ArrayList<>(List.of(messages));
        return Execution.builder().id("two-stage")
                .agentRequest(AgentRequest.builder()
                        .contextSpec(ContextSpec.builder()
                                .context(copy)
                                .build())
                        .build()).messages(copy).build();
    }

    private static ToolCallRequest call(String id, String arguments) {
        return new ToolCallRequest(id, "read", 0, arguments);
    }

    private static ToolMessageEntity tool(String id, String output, boolean error, Integer exitCode) {
        return ToolMessageEntity.builder().id(id).name("read").isError(error).exitCode(exitCode).toolOutput(output).build();
    }

    private static SystemMessageEntity system(String text) {
        return SystemMessageEntity.builder().text(text).build();
    }

    private static UserMessageEntity user(String text) {
        return UserMessageEntity.from(text);
    }

    private static ToolMessageEntity findTool(Execution execution, String id) {
        return execution.getMessages().stream().filter(ToolMessageEntity.class::isInstance)
                .map(ToolMessageEntity.class::cast).filter(tool -> id.equals(tool.getId())).findFirst().orElseThrow();
    }

    private static boolean hasTool(Execution execution, String id) {
        return execution.getMessages().stream().filter(ToolMessageEntity.class::isInstance)
                .map(ToolMessageEntity.class::cast).anyMatch(tool -> id.equals(tool.getId()));
    }

    private static AiMessageEntity lastAi(Execution execution) {
        return execution.getMessages().stream().filter(AiMessageEntity.class::isInstance)
                .map(AiMessageEntity.class::cast).reduce((first, second) -> second).orElseThrow();
    }

    private static List<String> assistantTexts(Execution execution) {
        return execution.getMessages().stream().filter(AiMessageEntity.class::isInstance).map(Message::text).toList();
    }
}
