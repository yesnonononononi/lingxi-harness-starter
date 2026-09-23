package com.summit.runtime.compact;

import com.summit.core.compact.ContextCompacter;
import com.summit.core.compact.ContextCompactRequest;
import com.summit.core.compact.ContextUsageMetric;
import com.summit.core.compact.ContextAttachmentProvider;
import com.summit.core.compact.Tokenizer;
import com.summit.core.conversation.event.ContextUpdateEvent;
import com.summit.core.conversation.event.RuntimeEventPublisher;
import com.summit.core.conversation.message.AiMessageEntity;
import com.summit.core.conversation.message.Message;
import com.summit.core.conversation.message.ToolMessageEntity;
import com.summit.runtime.agent.AgentConfig;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

import java.util.List;

/** Manual (local) compaction: calls no model and simply truncates the oldest tool rounds of the session. */
@Slf4j
@RequiredArgsConstructor
public class DefaultManualCompacter implements ContextCompacter {

    /** Old tool rounds processed per pass; fallback when no band decision is available. */
    private static final int DEFAULT_MAX_TRUNCATE_ROUNDS = 5;

    /** Tool results of a squeezed round are kept as short stubs (truncated by token count) so the pairing stays valid for the model. */
    private static final int TOOL_RESULT_STUB_TOKENS = 64;

    private final ContextAttachmentProvider contextAttachmentProvider;
    private final Tokenizer tokenizer;
    private final AgentConfig agentConfig;
    private final RuntimeEventPublisher runtimeEventPublisher;

    @Override
    public boolean compact(ContextCompactRequest request) {
        var execution = request.execution();
        List<Message> messages = execution.getMessages();
        if (messages == null || messages.isEmpty()) {
            log.warn("【context-squeeze】empty context, manual compact skipped: executionId={}", execution.getId());
            return false;
        }

        String protectedAttachment = contextAttachmentProvider.attachment(execution.getId()).orElse(null);

        publish(execution.getId(), ContextUpdateEvent.Phase.SQUEEZE_STARTED,
              null, "manual squeeze started");

        int maxRounds = maxRoundsOf(request);
        int processed = 0;

        for (int attempt = 0; attempt < maxRounds; attempt++) {
            int squeezed = squeezeOldestRound(messages, protectedAttachment);
            if (squeezed <= 0) {
                break;
            }
            processed += squeezed;
        }
        if (processed <= 0) {
            log.info("【context-squeeze】no older round can be squeezed further, skip: executionId={}", execution.getId());
            return false;
        }

        publish(execution.getId(), ContextUpdateEvent.Phase.SQUEEZE_COMPLETED,
                this.tokenizer.usage(messages,agentConfig.maxTokens()), "manual squeeze completed");
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

    /** Finds and squeezes the oldest squeezable round that does not carry the protected application state. */
    private int squeezeOldestRound(List<Message> messages, String protectedAttachment) {
        for (int i = 1; i < messages.size(); i++) {
            Message message = messages.get(i);
            if (!(message instanceof AiMessageEntity ai)) {
                continue;
            }
            if (protectedAttachment != null && !protectedAttachment.isBlank()
                    && protectedAttachment.equals(ai.text())) {
                // the round carrying the protected state is never truncated
                continue;
            }
            if (squeezeRoundAt(messages, i) > 0) {
                return 1;
            }
            // round already at its minimum: keep looking at later rounds
        }
        return 0;
    }

    /** Squeezes the tool round starting at {@code start} (an AiMessage): drops its text (keeps thinking), truncates the following tool-result messages into short stubs, and removes the whole round when it is already empty. */
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



    private void publish(String executionId, ContextUpdateEvent.Phase phase,
                         ContextUsageMetric usage, String prefix) {
        runtimeEventPublisher.onContextUpdate(new ContextUpdateEvent(executionId, phase, usage,
                usage == null ? prefix
                        : String.format("%s：当前上下文占用 %d / %d tokens（%.1f%%）", prefix,
                        usage.tokenCount(), usage.maxTokens(), usage.ratio() * 100)));
    }
}
