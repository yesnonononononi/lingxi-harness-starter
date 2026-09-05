package com.summit.runtime.compact;

import com.summit.core.compact.CompactSummaryResolver;
import com.summit.core.compact.ContextCompacter;
import com.summit.core.compact.ContextCompactRequest;
import com.summit.core.compact.ContextCompactionPrompt;
import com.summit.core.compact.ContextSummary;
import com.summit.core.compact.ContextUsageMetric;
import com.summit.core.compact.Tokenizer;
import com.summit.core.conversation.ConversationManager;
import com.summit.core.conversation.api.ChatRequestEntity;
import com.summit.core.conversation.api.ChatResponseEntity;
import com.summit.core.conversation.api.ToolCallRequest;
import com.summit.core.conversation.event.ContextUpdateEvent;
import com.summit.core.conversation.event.RuntimeEventPublisher;
import com.summit.core.conversation.message.AiMessageEntity;
import com.summit.core.conversation.message.Message;
import com.summit.core.conversation.message.SystemMessageEntity;
import com.summit.core.conversation.message.ToolMessageEntity;
import com.summit.core.conversation.message.UserMessageEntity;
import com.summit.core.model.ChatModel;
import com.summit.core.plan.PlanEntity;
import com.summit.core.plan.PlanStore;
import com.summit.runtime.agent.AgentConfig;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

import java.io.Serializable;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * Model deep compaction: asks the compact model ({@code defaultContextCompactModel}) for a summary of
 * the conversation history and rebuilds the session from it ({@code ConversationManager#rebuildContext}).
 *
 * <p>Serves the {@code ContextSqueezeRequest#expectAdvanceSqueeze()} band: triggered by the runtime
 * checkpoint and executed in a blocking way (the compact-model call is synchronous). Only after the
 * rebuild finishes does the main loop start its next round, so the main model no longer has to invoke
 * {@code compact_context} itself. A {@link ContextUpdateEvent} carrying the usage is published before
 * and after, so the front-end can render the context usage state.</p>
 */
@Slf4j
@RequiredArgsConstructor
public class DefaultModelCompacter implements ContextCompacter {

    private final ChatModel chatModel;
    private final ConversationManager conversationManager;
    private final PlanStore planStore;
    private final Tokenizer tokenizer;
    private final AgentConfig agentConfig;
    private final RuntimeEventPublisher runtimeEventPublisher;

    @Override
    public boolean compact(ContextCompactRequest request) {
        Serializable sessionId = request.sessionId();
        try {
            List<Message> messages = conversationManager.messages(sessionId);
            if (messages.isEmpty()) {
                log.warn("【context-compact】empty conversation, model compact skipped: sessionId={}", sessionId);
                return false;
            }

            publish(sessionId, request.executionId(), ContextUpdateEvent.Phase.SQUEEZE_STARTED,
                    usage(messages), "模型深度压缩已开始");

            Optional<PlanEntity> sessionPlan = planStore.findBySession(sessionId);
            StringBuilder systemPrompt = new StringBuilder(ContextCompactionPrompt.BASE_COMPACTION_PROMPT);
            sessionPlan.ifPresent(plan -> systemPrompt.append("\n").append(ContextCompactionPrompt.PLAN_PROTECTION_PROMPT));

            String history = renderConversation(messages);
            String payload = sessionPlan
                    .map(plan -> history + ContextCompactionPrompt.PROTECTED_PLAN_MARKER + plan.text())
                    .orElse(history);

            List<Message> compactMessages = new ArrayList<>();
            compactMessages.add(SystemMessageEntity.builder().text(systemPrompt.toString()).build());
            compactMessages.add(UserMessageEntity.from(payload));

            ChatRequestEntity compactRequest = ChatRequestEntity.builder()
                    .messages(compactMessages)
                    .build();
            ChatResponseEntity response = this.chatModel.chat(compactRequest);
            log.info("【compact-model】compact model responded: executionId={}, thinking={}",
                    request.executionId(), response.getAiMessageEntity().getThinking());

            ContextSummary summary = CompactSummaryResolver.resolve(response.getAiMessageEntity().text());
            if (summary == null) {
                log.warn("【context-compact】compact model returned no usable summary, context rebuild skipped, "
                        + "executionId={}", request.executionId());
                return false;
            }

            conversationManager.rebuildContext(summary, sessionId);
            publish(sessionId, request.executionId(), ContextUpdateEvent.Phase.SQUEEZE_COMPLETED,
                    usage(conversationManager.messages(sessionId)), "模型深度压缩完成，会话已重建");
            log.info("【context-compact】model compact done: sessionId={}, executionId={}", sessionId, request.executionId());
            return true;
        } catch (Exception e) {
            log.error("【context-compact】model compact failed: sessionId={}, executionId={}", sessionId, request.executionId(), e);
            return false;
        }
    }

    /**
     * Renders the session messages into a plain-text history as the compact-model input.
     * The first system message (the session's base boundary / system prompt) is skipped so the system
     * prompt is not fed into the compact model.
     */
    private String renderConversation(List<Message> messages) {
        StringBuilder history = new StringBuilder();
        for (int i = 0; i < messages.size(); i++) {
            Message message = messages.get(i);
            if (i == 0 && message instanceof SystemMessageEntity) {
                continue;
            }
            if (message instanceof SystemMessageEntity systemMessage) {
                appendLine(history, "[系统]", systemMessage.getText());
            } else if (message instanceof UserMessageEntity userMessage) {
                appendLine(history, "[用户]", userMessage.getText());
            } else if (message instanceof AiMessageEntity aiMessage) {
                appendLine(history, "[助手]", aiMessage.getText());
                if (aiMessage.getToolCalls() != null) {
                    for (ToolCallRequest call : aiMessage.getToolCalls()) {
                        if (call != null) {
                            appendLine(history, "[工具调用]", call.name() + "(" + call.arguments() + ")");
                        }
                    }
                }
            } else if (message instanceof ToolMessageEntity toolMessage) {
                String toolName = toolMessage.getName() == null ? "" : toolMessage.getName();
                appendLine(history, "[工具结果 " + toolName + "]", toolMessage.getText());
            }
        }
        return history.toString();
    }

    private void appendLine(StringBuilder history, String label, String content) {
        if (content == null || content.isBlank()) {
            return;
        }
        history.append(label).append(": ").append(content).append("\n");
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
