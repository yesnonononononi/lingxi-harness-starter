package com.summit.runtime.compact;

import com.summit.core.agent.AgentRequest;
import com.summit.core.agent.Execution;
import com.summit.core.compact.ContextCompactRequest;
import com.summit.core.conversation.api.ToolCallRequest;
import com.summit.core.conversation.event.RuntimeEventPublisher;
import com.summit.core.conversation.message.AiMessageEntity;
import com.summit.core.conversation.message.Message;
import com.summit.core.conversation.message.SystemMessageEntity;
import com.summit.core.conversation.message.ToolMessageEntity;
import com.summit.core.conversation.message.UserMessageEntity;
import com.summit.core.runtime.loop.ContextUsageReporter;
import com.summit.runtime.agent.AgentConfig;
import com.summit.runtime.conversation.DefaultTokenizer;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Budget behaviour of the prune band — cases 27 to 30 of §8.2.
 *
 * <p>Pruning never gives up because the non-cuttable part is large: it degrades every gradable
 * segment as far as the budget allows, accepts the overflow and reports it. What it does guarantee
 * is that the allocation itself never overruns the target, and that a generation whose budget is
 * entirely consumed by the non-cuttable part produces no skeleton at all.</p>
 */
class ContextPrunerBudgetTest {

    private final DefaultTokenizer tokenizer = new DefaultTokenizer();

    /**
     * Case 27: 100K budget, 82K non-cuttable, 120K of history — the pass must still reclaim about
     * the whole budget instead of abandoning the attempt.
     */
    @Test
    void pruneAlwaysReducesWhenAble() {
        int maxTokens = 250_000;
        AgentConfig config = AgentConfig.builder().maxTokens(maxTokens).pruneTargetRatio(0.40d).build();
        int budget = config.pruneBudgetTokens();
        assertEquals(100_000, budget);

        List<Message> messages = new ArrayList<>();
        messages.add(SystemMessageEntity.builder().text("system").build());
        messages.add(UserMessageEntity.from("u".repeat(246_000))); // about 82K tokens: non-cuttable
        for (int segment = 0; segment < 20; segment++) {
            messages.add(AiMessageEntity.builder().text("turn " + segment)
                    .toolCalls(List.of(new ToolCallRequest("c" + segment, "read", 0, "{}"))).build());
            messages.add(ToolMessageEntity.builder().id("c" + segment).name("read")
                    .toolOutput("x".repeat(18_000)).build()); // about 6K tokens each
        }
        Execution execution = execution(messages);
        int before = tokenizer.count(execution.getMessages());

        assertTrue(pruner(config).compact(new ContextCompactRequest(execution, null)));

        int after = tokenizer.count(execution.getMessages());
        assertTrue(after <= budget,
                "the allocation must never overrun the target: " + after + " > " + budget);
        assertTrue(after <= 110_000,
                "the product must land near the 100K target, not just below the input: " + after);
        assertTrue(after < before / 2,
                "pruning reclaims what it can instead of giving up: " + before + " -> " + after);
    }

    /** Case 28: when the non-cuttable part alone exceeds the budget, every segment is dropped. */
    @Test
    void pruneDropsAllSegmentsWhenNonCuttableExceedsBudget() {
        AgentConfig config = AgentConfig.builder().maxTokens(25_000).pruneTargetRatio(0.40d).build();
        List<Message> messages = new ArrayList<>();
        messages.add(SystemMessageEntity.builder().text("system").build());
        messages.add(UserMessageEntity.from("u".repeat(30_000))); // about 10K tokens = the whole budget
        for (int segment = 0; segment < 5; segment++) {
            messages.add(AiMessageEntity.builder().text("turn " + segment)
                    .toolCalls(List.of(new ToolCallRequest("c" + segment, "read", 0, "{}"))).build());
            messages.add(ToolMessageEntity.builder().id("c" + segment).name("read")
                    .toolOutput("x".repeat(3_000)).build());
        }
        Execution execution = execution(messages);

        pruner(config).compact(new ContextCompactRequest(execution, null));

        assertEquals(2, execution.getMessages().size(),
                "only the system message and the (non-cuttable) user message remain");
        assertTrue(execution.getMessages().stream().noneMatch(AiMessageEntity.class::isInstance));
        assertTrue(execution.getMessages().stream().noneMatch(ToolMessageEntity.class::isInstance));
    }

    /** Case 29: the allocation never exceeds the target, whatever the ratio. */
    @ParameterizedTest
    @ValueSource(doubles = {0.10d, 0.20d, 0.40d})
    void budgetAllocationNeverExceedsTarget(double ratio) {
        AgentConfig config = AgentConfig.builder().maxTokens(50_000).pruneTargetRatio(ratio).build();
        int budget = config.pruneBudgetTokens();

        List<Message> messages = new ArrayList<>();
        messages.add(SystemMessageEntity.builder().text("system").build());
        messages.add(UserMessageEntity.from("u".repeat(3_000)));
        for (int segment = 0; segment < 5; segment++) {
            messages.add(AiMessageEntity.builder().text("turn " + segment)
                    .toolCalls(List.of(new ToolCallRequest("c" + segment, "read", 0, "{}"))).build());
            messages.add(ToolMessageEntity.builder().id("c" + segment).name("read")
                    .toolOutput("x".repeat(6_000)).build());
        }
        Execution execution = execution(messages);

        pruner(config).compact(new ContextCompactRequest(execution, null));

        int after = tokenizer.count(execution.getMessages());
        assertTrue(after <= budget, "ratio=" + ratio + ": " + after + " exceeds the budget " + budget);
    }

    /** Case 30: no budget left means no skeleton — placeholders are not free either. */
    @Test
    void skeletonIsEmptyWhenNoBudgetRemains() {
        AgentConfig config = AgentConfig.builder().maxTokens(25_000).pruneTargetRatio(0.40d).build();
        List<Message> messages = new ArrayList<>();
        messages.add(SystemMessageEntity.builder().text("system").build());
        messages.add(UserMessageEntity.from("u".repeat(30_000)));
        for (int segment = 0; segment < 3; segment++) {
            messages.add(AiMessageEntity.builder().text("turn " + segment)
                    .toolCalls(List.of(new ToolCallRequest("c" + segment, "read", 0, "{}"))).build());
            messages.add(ToolMessageEntity.builder().id("c" + segment).name("read")
                    .toolOutput("x".repeat(3_000)).build());
        }
        Execution execution = execution(messages);

        pruner(config).compact(new ContextCompactRequest(execution, null));

        assertTrue(execution.getMessages().stream().map(Message::text)
                        .noneMatch(text -> text != null && text.startsWith("[output omitted ·")),
                "with no budget left not even a skeleton is written");
    }

    // ------------------------------------------------------------------ fixtures

    private DefaultContextPruner pruner(AgentConfig config) {
        return new DefaultContextPruner(config,
                new ContextUsageReporter(tokenizer, config.maxTokens(),
                        new RuntimeEventPublisher(List.of()), 1));
    }

    private static Execution execution(List<Message> messages) {
        List<Message> list = new ArrayList<>(messages);
        return Execution.builder().id("execution-1")
                .agentRequest(AgentRequest.builder().messages(list).build())
                .messages(list).build();
    }
}
