package com.summit.runtime.loop;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.summit.core.agent.AgentRequest;
import com.summit.core.agent.Execution;
import com.summit.core.compact.ContextAttachmentProvider;
import com.summit.core.compact.ContextCompactRequest;
import com.summit.core.conversation.api.ChatResponseEntity;
import com.summit.core.conversation.api.ToolCallRequest;
import com.summit.core.conversation.event.ContextUpdateEvent;
import com.summit.core.conversation.event.RuntimeEventPublisher;
import com.summit.core.conversation.message.*;
import com.summit.core.exception.TokenBudgetExceededException;
import com.summit.core.runtime.loop.ContextUsageReporter;
import com.summit.core.tool.*;
import com.summit.runtime.agent.AgentConfig;
import com.summit.runtime.compact.DefaultCompactSummaryApplier;
import com.summit.runtime.compact.DefaultContextPruner;
import com.summit.runtime.compact.DefaultModelCompacter;
import com.summit.runtime.conversation.DefaultConversationManager;
import com.summit.runtime.conversation.DefaultTokenizer;
import openAi.OpenAiTokenizer;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

class ContextUsageBaselineTest {
    @Test
    void initialAndResumedContextNeverSeedFromProviderUsage() throws Exception {
        DefaultTokenizer tokenizer = new DefaultTokenizer();
        ContextUsageReporter usage = reporter(tokenizer);
        Execution execution = execution(UserMessageEntity.from("context"));
        TokenUsageEntity billed = execution.getTokenUsage();
        TokenUsageEntity baseline = usage.baseline(execution);
        assertEquals(tokenizer.count(execution.getMessages()), baseline.getTotalTokens());
        assertNotSame(billed, baseline);
        assertEquals(12345, billed.getTotalTokens());
        assertFalse(execution.toString().contains("contextTokenBaseline"));

        ObjectMapper mapper = new ObjectMapper();
        String json = mapper.writeValueAsString(execution);
        assertFalse(json.contains("contextTokenBaseline"));
        Execution restored = mapper.readValue(json, Execution.class);
        assertNull(restored.getContextTokenBaseline());
        assertEquals(baseline.getTotalTokens(), usage.baseline(restored).getTotalTokens());
        assertEquals(12345, restored.getTokenUsage().getTotalTokens());
    }

    @Test
    void appendedRoundsAndInjectedInputCountOnlyTheSuffixAndDoNotRepeatThePrimer() {
        CountingTokenizer tokenizer = new CountingTokenizer();
        ContextUsageReporter usage = reporter(tokenizer);
        Execution execution = execution(UserMessageEntity.from("history\n"), UserMessageEntity.from("tail\n"));
        TokenUsageEntity baseline = usage.baseline(execution);
        tokenizer.largestBatch = 0;
        for (int round = 0; round < 20; round++) {
            execution.getMessages().add(AiMessageEntity.builder().text("answer\n").build());
            execution.getMessages().add(UserMessageEntity.from("external input\n"));
            assertSame(baseline, usage.baseline(execution));
            assertEquals(tokenizer.expected(execution.getMessages()), baseline.getTotalTokens());
            usage.report(execution);
        }
        assertTrue(tokenizer.largestBatch <= 3, "ordinary rounds must not re-tokenize growing history");
        assertEquals(12345, execution.getTokenUsage().getTotalTokens());
    }

    @Test
    void realBpeIncrementTracksTheFullCountAcrossToolRounds() {
        DefaultTokenizer tokenizer = new DefaultTokenizer(new OpenAiTokenizer());
        ContextUsageReporter usage = reporter(tokenizer);
        Execution execution = execution(UserMessageEntity.from("initial request\n"));
        usage.baseline(execution);
        for (int round = 0; round < 20; round++) {
            execution.getMessages().add(AiMessageEntity.builder().text("answer\n")
                    .toolCalls(List.of(new ToolCallRequest("c" + round, "read", 0, "{\"path\":\"a.txt\"}"))).build());
            execution.getMessages().add(ToolMessageEntity.builder().id("c" + round).name("read")
                    .toolOutput("output 文件 内容\n").build());
            assertEquals(tokenizer.count(execution.getMessages()), usage.baseline(execution).getTotalTokens());
        }
    }

    @Test
    void completionRebuildsTheSameBaselineEvenWithNoPublisherOrNoRewrite() {
        DefaultTokenizer tokenizer = new DefaultTokenizer();
        ContextUsageReporter usage = reporter(tokenizer);
        Execution execution = execution(UserMessageEntity.from("old context"));
        TokenUsageEntity baseline = usage.baseline(execution);
        for (ContextUpdateEvent.Band band : ContextUpdateEvent.Band.values()) {
            execution.setMessages(new ArrayList<>(List.of(UserMessageEntity.from("summary " + band))));
            usage.publish(execution, ContextUpdateEvent.Phase.SQUEEZE_COMPLETED, band, "done");
            assertSame(baseline, usage.baseline(execution));
            assertEquals(tokenizer.count(execution.getMessages()), baseline.getTotalTokens());
        }
        usage.publish(execution, ContextUpdateEvent.Phase.SQUEEZE_COMPLETED, "no rewrite");
        assertSame(baseline, usage.baseline(execution));
    }

    @Test
    void notificationFailureCannotPreventRebuildingButTokenizerFailureEscapes() {
        AtomicInteger notifications = new AtomicInteger();
        RuntimeEventPublisher publisher = new RuntimeEventPublisher(List.of()) {
            @Override public void onContextUpdate(ContextUpdateEvent event) {
                notifications.incrementAndGet();
                throw new IllegalStateException("notification unavailable");
            }
        };
        CountingTokenizer tokenizer = new CountingTokenizer();
        ContextUsageReporter usage = new ContextUsageReporter(tokenizer, 1000, publisher, 5);
        Execution execution = execution(UserMessageEntity.from("history"));
        TokenUsageEntity baseline = usage.baseline(execution);
        execution.setMessages(new ArrayList<>(List.of(UserMessageEntity.from("summary"))));
        assertDoesNotThrow(() -> usage.publish(execution, ContextUpdateEvent.Phase.SQUEEZE_COMPLETED, "done"));
        assertEquals(tokenizer.expected(execution.getMessages()), baseline.getTotalTokens());
        assertEquals(1, notifications.get());
        tokenizer.fail = true;
        assertThrows(IllegalStateException.class,
                () -> usage.publish(execution, ContextUpdateEvent.Phase.SQUEEZE_COMPLETED, "done"));
        assertEquals(1, notifications.get());
    }

    @Test
    void failedIncrementCanBeRetriedWithoutDoubleCounting() {
        CountingTokenizer tokenizer = new CountingTokenizer();
        ContextUsageReporter usage = reporter(tokenizer);
        Execution execution = execution(UserMessageEntity.from("history"));
        TokenUsageEntity baseline = usage.baseline(execution);
        int before = baseline.getTotalTokens();
        execution.getMessages().add(UserMessageEntity.from("new input"));
        tokenizer.failOnCall = tokenizer.calls + 2;
        assertThrows(IllegalStateException.class, () -> usage.baseline(execution));
        assertEquals(before, baseline.getTotalTokens());
        tokenizer.failOnCall = 0;
        assertEquals(tokenizer.expected(execution.getMessages()), usage.baseline(execution).getTotalTokens());
    }

    @Test
    void toolWhitelistChangesAndEmptyHistoryInvalidateTheBaseline() {
        CountingTokenizer tokenizer = new CountingTokenizer();
        ToolDefinition<?> tool = ToolDefinition.builder().id("read").name("read").description("reads files")
                .maxOutput(100).timeout(1L).executor(call -> ToolExecuteResult.success("ok")).build();
        ToolRegistry registry = new ToolRegistry(List.of(tool));
        ContextUsageReporter usage = new ContextUsageReporter(tokenizer, 1000, null, 5, () -> registry);
        Execution execution = execution();
        execution.getAgentRequest().setToolList(List.of("read"));
        usage.baseline(execution);
        execution.getMessages().add(UserMessageEntity.from("input"));
        assertEquals(tokenizer.count(execution.getMessages(), List.of(tool)), usage.baseline(execution).getTotalTokens());
        execution.getAgentRequest().setToolList(List.of());
        assertEquals(tokenizer.expected(execution.getMessages()), usage.baseline(execution).getTotalTokens());
    }

    @Test
    void pruneCompletionRebuildsTheSharedBaseline() {
        DefaultTokenizer tokenizer = new DefaultTokenizer();
        ContextUsageReporter usage = reporter(tokenizer);
        Execution execution = history();
        TokenUsageEntity baseline = usage.baseline(execution);
        int before = baseline.getTotalTokens();
        assertTrue(new DefaultContextPruner(config(2), usage).compact(new ContextCompactRequest(execution)));
        assertTrue(baseline.getTotalTokens() < before);
        assertEquals(tokenizer.count(execution.getMessages()), baseline.getTotalTokens());
    }

    @Test
    void oversizedSummaryChainsBeforeTheNextModelAndRebuildsOncePerCompletion() {
        assertSummaryBudget(false);
    }

    @Test
    void exhaustedOversizedSummaryFallsBackAndPropagatesIrreducibleBudgetError() {
        assertSummaryBudget(true);
    }

    private void assertSummaryBudget(boolean alwaysOversized) {
        DefaultTokenizer tokenizer = new DefaultTokenizer();
        ContextUsageReporter usage = reporter(tokenizer);
        Execution execution = history();
        TokenUsageEntity baseline = usage.baseline(execution);
        AtomicInteger attempts = new AtomicInteger();
        DefaultConversationManager conversations = new DefaultConversationManager(ContextAttachmentProvider.NONE);
        DefaultModelCompacter compacter = DefaultModelCompacter.builder().models(ignored -> request -> {
            int attempt = attempts.incrementAndGet();
            String summary = alwaysOversized || attempt == 1 ? "x".repeat(6000) : "short summary";
            return ChatResponseEntity.builder().aiMessageEntity(AiMessageEntity.builder()
                    .text("{\"summary\":\"" + summary + "\"}").build()).build();
        }).conversationManager(conversations).contextAttachmentProvider(ContextAttachmentProvider.NONE)
                .usage(usage).summaryApplier(new DefaultCompactSummaryApplier(conversations)).build();
        BoundaryChecker checker = new BoundaryChecker(config(2), tokenizer, conversations,
                new DefaultContextPruner(config(2), usage), compacter, usage, null);
        if (alwaysOversized) {
            assertThrows(TokenBudgetExceededException.class, () -> checker.before(baseline, execution));
            assertTrue(execution.getCompactionState().summaryUnavailable());
            assertEquals(2, execution.getCompactionState().summaryAttempts());
        } else {
            checker.before(baseline, execution);
            assertTrue(baseline.getTotalTokens() < 850);
            assertEquals(0, execution.getCompactionState().summaryAttempts());
        }
        assertEquals(2, attempts.get());
        assertEquals(tokenizer.count(execution.getMessages()), baseline.getTotalTokens());
        assertEquals(12345, execution.getTokenUsage().getTotalTokens());
    }

    private static AgentConfig config(int attempts) {
        return AgentConfig.builder().maxTokens(1000).maxSummaryAttempts(attempts).build();
    }

    private static ContextUsageReporter reporter(DefaultTokenizer tokenizer) {
        return new ContextUsageReporter(tokenizer, 1000, null, 5);
    }

    private static Execution history() {
        return execution(UserMessageEntity.from("old request"),
                AiMessageEntity.builder().toolCalls(List.of(new ToolCallRequest("c", "read", 0, "{}"))).build(),
                ToolMessageEntity.builder().id("c").name("read").toolOutput("huge output ".repeat(1000)).build(),
                UserMessageEntity.from("current request"));
    }

    private static Execution execution(Message... input) {
        List<Message> messages = new ArrayList<>(List.of(input));
        return Execution.builder().id("baseline-test").agentId("a").messages(messages)
                .tokenUsage(TokenUsageEntity.of(12345, 12000, 345))
                .agentRequest(AgentRequest.builder().messages(messages).build()).build();
    }

    private static class CountingTokenizer extends DefaultTokenizer {
        int largestBatch;
        boolean fail;
        int calls;
        int failOnCall;
        int expected(List<Message> messages) {
            return 3 + messages.stream().mapToInt(message -> 4 + message.text().length()).sum();
        }
        @Override public int count(List<Message> messages, List<ToolDefinition<?>> tools) {
            if (fail || ++calls == failOnCall) throw new IllegalStateException("tokenizer unavailable");
            largestBatch = Math.max(largestBatch, messages.size());
            return expected(messages) + tools.size() * 17;
        }
    }
}
