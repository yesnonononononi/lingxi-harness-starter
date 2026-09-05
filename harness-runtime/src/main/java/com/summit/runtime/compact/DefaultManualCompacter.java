package com.summit.runtime.compact;

import com.summit.core.compact.ContextCompacter;
import com.summit.core.compact.ContextCompactRequest;
import com.summit.core.compact.ContextUsageMetric;
import com.summit.core.compact.Tokenizer;
import com.summit.core.conversation.ConversationEntity;
import com.summit.core.conversation.ConversationStore;
import com.summit.core.conversation.event.ContextUpdateEvent;
import com.summit.core.conversation.event.RuntimeEventPublisher;
import com.summit.core.conversation.message.AiMessageEntity;
import com.summit.core.conversation.message.Message;
import com.summit.core.conversation.message.ToolMessageEntity;
import com.summit.core.plan.PlanEntity;
import com.summit.core.plan.PlanStore;
import com.summit.runtime.agent.AgentConfig;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

import java.io.Serializable;
import java.util.List;
import java.util.Optional;

/**
 * Manual (local) compaction: calls no model and simply truncates the oldest tool rounds of the session.
 *
 * <ul>
 *   <li>a squeezed round keeps its {@link AiMessageEntity} thinking and drops the text;</li>
 *   <li>the round's tool-result messages are truncated into a short stub (the AiMessage-&gt;tool pairing stays intact);</li>
 *   <li>a round already at its minimum (no thinking / tool call / tool result) is removed entirely.</li>
 * </ul>
 *
 * <p>Serves the {@code ContextSqueezeRequest#shouldSqueeze()} band: triggered by the runtime checkpoint
 * and executed in a blocking way — only after it returns does the main loop start its next round.
 * A {@link ContextUpdateEvent} carrying the usage is published before and after, so the front-end can
 * render the context usage state.</p>
 */
@Slf4j
@RequiredArgsConstructor
public class DefaultManualCompacter implements ContextCompacter {

    /** Old tool rounds processed per pass; fallback when no band decision is available. */
    private static final int DEFAULT_MAX_TRUNCATE_ROUNDS = 5;

    /** Tool results of a squeezed round are kept as short stubs (truncated by token count) so the pairing stays valid for the model. */
    private static final int TOOL_RESULT_STUB_TOKENS = 64;

    private final ConversationStore conversationStore;
    private final PlanStore planStore;
    private final Tokenizer tokenizer;
    private final AgentConfig agentConfig;
    private final RuntimeEventPublisher runtimeEventPublisher;

    @Override
    public boolean compact(ContextCompactRequest request) {
        Serializable sessionId = request.sessionId();
        Optional<ConversationEntity> entityOptional = conversationStore.get(sessionId);
        if (entityOptional.isEmpty()) {
            log.warn("【context-squeeze】conversation not found, manual compact skipped: sessionId={}", sessionId);
            return false;
        }
        ConversationEntity conversation = entityOptional.get();
        if (conversation.messages().isEmpty()) {
            log.warn("【context-squeeze】empty conversation, manual compact skipped: sessionId={}", sessionId);
            return false;
        }

        // A produced plan must be kept verbatim and can never be truncated away
        String protectedPlanText = planStore.findBySession(sessionId).map(PlanEntity::text).orElse(null);

        publish(sessionId, request.executionId(), ContextUpdateEvent.Phase.SQUEEZE_STARTED,
                usage(conversation.messages()), "本地手动压缩（按轮截断）已开始");

        int beforeTokens = tokenizer.count(conversation.messages());
        int maxRounds = maxRoundsOf(request);
        int processed = 0;
        for (int attempt = 0; attempt < maxRounds; attempt++) {
            int squeezed = squeezeOldestRound(conversation.messages(), protectedPlanText);
            if (squeezed <= 0) {
                break;
            }
            processed += squeezed;
        }
        if (processed <= 0) {
            log.info("【context-squeeze】no older round can be squeezed further, skip: sessionId={}", sessionId);
            return false;
        }

        conversationStore.save(sessionId, conversation);
        int afterTokens = tokenizer.count(conversation.messages());
        publish(sessionId, request.executionId(), ContextUpdateEvent.Phase.SQUEEZE_COMPLETED,
                usage(conversation.messages()), "本地手动压缩完成");
        log.info("【context-squeeze】manual compact done: sessionId={}, {} round(s) squeezed, tokens: {} -> {}",
                sessionId, processed, beforeTokens, afterTokens);
        return true;
    }

    /** Rounds this pass may process at most: the band decision wins, otherwise the default applies. */
    private int maxRoundsOf(ContextCompactRequest request) {
        if (request.decision() != null && request.decision().truncateTurn() != null
                && request.decision().truncateTurn() > 0) {
            return request.decision().truncateTurn();
        }
        return DEFAULT_MAX_TRUNCATE_ROUNDS;
    }

    /**
     * Finds and squeezes the oldest squeezable round that does not carry the protected plan.
     *
     * @return 1 when a round was actually rewritten; 0 when there is nothing to squeeze
     */
    private int squeezeOldestRound(List<Message> messages, String protectedPlanText) {
        for (int i = 1; i < messages.size(); i++) {
            Message message = messages.get(i);
            if (!(message instanceof AiMessageEntity ai)) {
                continue;
            }
            if (protectedPlanText != null && !protectedPlanText.isBlank()
                    && protectedPlanText.equals(ai.text())) {
                // the round carrying the plan is never truncated
                continue;
            }
            if (squeezeRoundAt(messages, i) > 0) {
                return 1;
            }
            // round already at its minimum: keep looking at later rounds
        }
        return 0;
    }

    /**
     * Squeezes the tool round starting at {@code start} (an AiMessage): drops its text (keeps thinking),
     * truncates the following tool-result messages into short stubs, and removes the whole round when it
     * is already empty.
     *
     * @return 1 when the round was rewritten; 0 when it is already at its minimum
     */
    private int squeezeRoundAt(List<Message> messages, int start) {
        AiMessageEntity ai = (AiMessageEntity) messages.get(start);
        boolean hasText = ai.text() != null && !ai.text().isBlank();
        boolean hasThinking = ai.getThinking() != null && !ai.getThinking().isBlank();
        boolean hasToolCalls = ai.getToolCalls() != null && !ai.getToolCalls().isEmpty();

        int end = start + 1;
        while (end < messages.size() && messages.get(end) instanceof ToolMessageEntity) {
            end++;
        }
        boolean hasToolMessages = end - start - 1 > 0;

        boolean modified = false;
        if (hasText) {
            ai.setText(null);
            modified = true;
        }
        if (hasToolMessages) {
            for (int j = start + 1; j < end; j++) {
                ToolMessageEntity tool = (ToolMessageEntity) messages.get(j);
                String truncated = tool.text() == null ? null : tokenizer.truncate(tool.text(), TOOL_RESULT_STUB_TOKENS);
                tool.setText(truncated);
            }
            modified = true;
        }
        if (!hasThinking && !hasToolCalls && !hasToolMessages) {
            // a pure-text round has been fully consumed (no thinking / tool call / tool result): remove it
            messages.subList(start, end).clear();
            return 1;
        }
        return modified ? 1 : 0;
    }

    private ContextUsageMetric usage(List<Message> messages) {
        Integer maxTokens = agentConfig.maxTokens();
        if (maxTokens == null || maxTokens <= 0) {
            return null;
        }
        return new ContextUsageMetric(
                tokenizer.count(messages),
                maxTokens,
                tokenizer.calcCurrentTokenRatio(messages, maxTokens));
    }

    private void publish(Serializable sessionId, String executionId, ContextUpdateEvent.Phase phase,
                         ContextUsageMetric usage, String prefix) {
        runtimeEventPublisher.onContextUpdate(new ContextUpdateEvent(sessionId, executionId, phase, usage,
                usage == null ? prefix
                        : String.format("%s：当前上下文占用 %d / %d tokens（%.1f%%）", prefix,
                        usage.tokenCount(), usage.maxTokens(), usage.ratio() * 100)));
    }
}
