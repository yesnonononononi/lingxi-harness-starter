package com.summit.runtime.compact;

import com.summit.adapter.langchain4j.codec.token.TokenEstimatorAdapter;
import com.summit.core.agent.AgentRequest;
import com.summit.core.agent.Execution;
import com.summit.core.compact.ContextCompactRequest;
import com.summit.core.compact.ContextUsageMetric;
import com.summit.core.compact.Tokenizer;
import com.summit.core.conversation.api.ToolCallRequest;
import com.summit.core.conversation.event.RuntimeEventPublisher;
import com.summit.core.conversation.message.AiMessageEntity;
import com.summit.core.conversation.message.Message;
import com.summit.core.conversation.message.SystemMessageEntity;
import com.summit.core.conversation.message.ToolMessageEntity;
import com.summit.core.conversation.message.UserMessageEntity;
import com.summit.core.runtime.loop.ContextUsageReporter;
import com.summit.runtime.agent.AgentConfig;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Regression for defect 2: the tool-output cap of a {@code FULL} segment must be bounded and
 * idempotent under a real tokenizer.
 *
 * <p>The old cap extrapolated an average chars-per-token ratio from the whole string and cut once
 * without re-counting. A dense prefix (CJK, symbols) carries far more tokens per character than a
 * sparse suffix, so the single cut left well over the cap — and because the ratio changed on every
 * pass, a second prune over the same product produced a different (shorter) string.</p>
 *
 * <p>This test uses the real OpenAI BPE counter ({@code gpt-4o}) and never the chars/3 fallback, so
 * a wrong implementation cannot pass by accident.</p>
 */
class ContextPrunerCapOutputTest {

    /** The configured token cap; deliberately small to expose an over-eager cut. */
    private static final int CAP = 100;

    private final TokenEstimatorAdapter bpe = new TokenEstimatorAdapter("gpt-4o");
    private final Tokenizer tokenizer = bpeTokenizer(bpe);

    @Test
    void fullSegmentHonoursTheTokenCapOnADensePrefixAndIsIdempotent() {
        // High token density per character in front, low density behind it: the exact shape that
        // defeats an average-ratio extrapolation.
        String densePrefix = "漢字記号【】±§".repeat(120);
        String sparseSuffix = "lorem ipsum dolor sit amet ".repeat(400);
        String payload = densePrefix + sparseSuffix;

        Execution execution = execution(
                SystemMessageEntity.builder().text("system").build(),
                UserMessageEntity.from("question"),
                AiMessageEntity.builder()
                        .toolCalls(List.of(new ToolCallRequest("c1", "read", 0, "{}"))).build(),
                ToolMessageEntity.builder().id("c1").name("read").toolOutput(payload).build());

        AgentConfig config = AgentConfig.builder().maxTokens(1_000_000).pruneTargetRatio(0.9d)
                .fullToolOutputCap(CAP).build();
        DefaultContextPruner pruner = new DefaultContextPruner(config, reporter());

        assertTrue(pruner.compact(new ContextCompactRequest(execution, null)),
                "a generously-budgeted FULL segment is trimmed to the cap");

        ToolMessageEntity kept = assertInstanceOf(ToolMessageEntity.class, execution.getMessages().get(3));
        int keptTokens = bpe.estimateText(kept.text());
        assertTrue(keptTokens <= CAP,
                "the kept output must really fit the token cap; was " + keptTokens + " tokens");
        assertFalse(kept.text().isEmpty(), "a capped output is never empty");
        assertFalse(kept.text().contains("[OUTPUT_TRUNCATED]"),
                "no acquisition-time truncation marker is introduced");
        assertTrue(payload.startsWith(kept.text()), "the cap keeps the head of the output");

        // Idempotency: a second pass over the same already-capped product must be byte-identical.
        List<String> firstProduct = textsOf(execution);
        assertFalse(pruner.compact(new ContextCompactRequest(execution, null)),
                "a second pass over an already-capped product must report no change");
        assertEquals(firstProduct, textsOf(execution), "the capped product is a fixed point");
    }

    /** A tool output that merely STARTS with the placeholder text must still be capped. */
    @Test
    void aToolOutputThatStartsWithThePlaceholderPrefixIsStillCapped() {
        String payload = "[output omitted · example from a file]\n" + "x".repeat(4_000);

        Execution execution = execution(
                SystemMessageEntity.builder().text("system").build(),
                UserMessageEntity.from("question"),
                AiMessageEntity.builder()
                        .toolCalls(List.of(new ToolCallRequest("c1", "read", 0, "{}"))).build(),
                ToolMessageEntity.builder().id("c1").name("read").toolOutput(payload).build());

        AgentConfig config = AgentConfig.builder().maxTokens(1_000_000).pruneTargetRatio(0.9d)
                .fullToolOutputCap(CAP).build();
        DefaultContextPruner pruner = new DefaultContextPruner(config, reporter());

        assertTrue(pruner.compact(new ContextCompactRequest(execution, null)),
                "the oversized FULL tool output is trimmed");

        ToolMessageEntity kept = assertInstanceOf(ToolMessageEntity.class, execution.getMessages().get(3));
        int keptTokens = bpe.estimateText(kept.text());
        assertTrue(keptTokens <= CAP,
                "a placeholder-prefixed tool output must still be capped; was " + keptTokens + " tokens");
        assertTrue(payload.startsWith(kept.text()), "the cap keeps the head");
        assertTrue(kept.text().length() < payload.length(), "it must be cut, not bypassed");
    }

    /** A SKELETON tool output that starts with the placeholder text is replaced by one-line placeholder. */
    @Test
    void aSkeletonToolOutputThatStartsWithThePlaceholderPrefixBecomesOneLine() {
        String payload = "[output omitted · example from a file]\n" + "x".repeat(4_000);

        Execution execution = execution(
                SystemMessageEntity.builder().text("system").build(),
                UserMessageEntity.from("question"),
                AiMessageEntity.builder()
                        .toolCalls(List.of(new ToolCallRequest("c1", "read", 0, "{}"))).build(),
                ToolMessageEntity.builder().id("c1").name("read").toolOutput(payload).build());

        // fullSegmentCount 0 => no segment is FULL, so the tool result is SKELETONized.
        AgentConfig config = AgentConfig.builder().maxTokens(1_000_000).pruneTargetRatio(0.9d)
                .fullSegmentCount(0).build();
        DefaultContextPruner pruner = new DefaultContextPruner(config, reporter());

        assertTrue(pruner.compact(new ContextCompactRequest(execution, null)));

        ToolMessageEntity kept = assertInstanceOf(ToolMessageEntity.class, execution.getMessages().get(3));
        assertTrue(kept.text().startsWith("[output omitted"), kept.text());
        assertFalse(kept.text().contains("\n"),
                "the placeholder must be a single line even when the raw output starts with the prefix: "
                        + kept.text());
        assertFalse(kept.text().equals(payload), "the raw multi-line body is not kept verbatim");
    }

    /** A single-line text that starts with the prefix and ends with ] is NOT a placeholder. */
    @Test
    void aSingleLinePrefixAndBracketTextIsStillSkeletonized() {
        String payload = "[output omitted · " + "x".repeat(4_000) + "]";

        Execution execution = execution(
                SystemMessageEntity.builder().text("system").build(),
                UserMessageEntity.from("question"),
                AiMessageEntity.builder()
                        .toolCalls(List.of(new ToolCallRequest("c1", "read", 0, "{}"))).build(),
                ToolMessageEntity.builder().id("c1").name("read").toolOutput(payload).build());

        AgentConfig config = AgentConfig.builder().maxTokens(1_000_000).pruneTargetRatio(0.9d)
                .fullSegmentCount(0).build();
        DefaultContextPruner pruner = new DefaultContextPruner(config, reporter());

        assertTrue(pruner.compact(new ContextCompactRequest(execution, null)));

        ToolMessageEntity kept = assertInstanceOf(ToolMessageEntity.class, execution.getMessages().get(3));
        assertFalse(kept.text().equals(payload), "a single-line prefix+bracket text is not our placeholder");
        assertTrue(kept.text().startsWith("[output omitted"), kept.text());
        assertFalse(kept.text().contains("\n"));
    }

    // ------------------------------------------------------------------ fixtures

    private ContextUsageReporter reporter() {
        return new ContextUsageReporter(tokenizer, 1_000_000, new RuntimeEventPublisher(List.of()), 1);
    }

    /** A {@link Tokenizer} whose counting is delegated to the real BPE estimator. */
    private static Tokenizer bpeTokenizer(TokenEstimatorAdapter bpe) {
        return new Tokenizer() {
            @Override
            public int count(List<Message> messages) {
                int total = 0;
                for (Message message : messages) {
                    total += bpe.estimateText(message.text());
                }
                return total;
            }

            @Override
            public String truncate(String output, int maxOutput) {
                return output;
            }

            @Override
            public double calcCurrentTokenRatio(List<Message> messages, int maxTokens) {
                return maxTokens <= 0 ? 0d : (double) count(messages) / maxTokens;
            }

            @Override
            public ContextUsageMetric usage(List<Message> messages, Integer maxTokens) {
                return ContextUsageMetric.of(count(messages), maxTokens == null ? 0 : maxTokens);
            }
        };
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
