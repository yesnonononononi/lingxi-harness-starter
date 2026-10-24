package com.summit.runtime.compact;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.summit.core.agent.CompactionState;
import com.summit.core.agent.Execution;
import com.summit.core.compact.ContextAttachmentProvider;
import com.summit.core.compact.ContextCompactRequest;
import com.summit.core.compact.ContextCompacter;
import com.summit.core.conversation.ConversationManager;
import com.summit.core.conversation.api.ChatRequestEntity;
import com.summit.core.conversation.api.ChatResponseEntity;
import com.summit.core.conversation.api.ToolCallRequest;
import com.summit.core.conversation.event.ContextUpdateEvent;
import com.summit.core.conversation.message.*;
import com.summit.core.model.chat.ChatModel;
import com.summit.core.runtime.loop.ContextUsageReporter;
import com.summit.core.tool.ToolExecuteResult;
import com.summit.core.tool.ToolExecution;
import com.summit.core.tool.ToolExecutor;
import com.summit.core.tool.ToolResultType;
import lombok.extern.slf4j.Slf4j;

import java.util.List;
import java.util.Optional;

/**
 * Both automatic and tool-requested model compaction use the same generation pipeline.
 *
 * <p><b>Chained summarization</b> (§3.2): the first round of a trigger window summarizes the
 * rendered history, and every following round summarizes <b>the previous summary</b> instead of the
 * original history. Retrying the full history costs a full-size input for a result of the same
 * shape, while compressing the model's own previous output is both cheap and the thing the model
 * does best. The chain is stateless here: a rebuild replaces the history with the summary, so the
 * result of the rebuild <em>is</em> the next input — this class reads the session at call time and
 * therefore never holds per-execution state.</p>
 *
 * <p>How many rounds a window may run is decided by the caller
 * ({@code BoundaryChecker}), which also owns the attempt counter and the "summarization is closed"
 * latch on {@link Execution#getCompactionState()}.</p>
 */
@Slf4j
public class DefaultModelCompacter implements ContextCompacter, ToolExecutor {
    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();
    private final ChatModel chatModel;
    private final ConversationManager conversationManager;
    private final ContextAttachmentProvider contextAttachmentProvider;
    private final ContextUsageReporter usage;
    private final CompactSummaryApplier summaryApplier;

    public DefaultModelCompacter(ChatModel chatModel, ConversationManager conversationManager,
                                 ContextAttachmentProvider contextAttachmentProvider, ContextUsageReporter usage) {
        this.chatModel = chatModel;
        this.conversationManager = conversationManager;
        this.contextAttachmentProvider = contextAttachmentProvider;
        this.usage = usage;
        this.summaryApplier = new CompactSummaryApplier(conversationManager);
    }

    @Override
    public boolean compact(ContextCompactRequest request) {
        var execution = request.execution();
        var messages = conversationManager.messages(execution);
        if (messages.isEmpty()) return false;
        usage.publish(execution, ContextUpdateEvent.Phase.SQUEEZE_STARTED,
                ContextUpdateEvent.Band.SUMMARY, "Model compaction started");
        try {
            if (!summaryApplier.apply(
                    generate(execution.getId(), summaryInput(execution, messages)), execution, false)) {
                // STARTED is already out: every failure path closes the pair, otherwise consumers
                // stay on the in-flight state until the next plain UPDATE happens to arrive.
                usage.publish(execution, ContextUpdateEvent.Phase.SQUEEZE_COMPLETED,
                        ContextUpdateEvent.Band.SUMMARY, "Model compaction finished without rewriting the context");
                return false;
            }
            usage.publish(execution, ContextUpdateEvent.Phase.SQUEEZE_COMPLETED,
                    ContextUpdateEvent.Band.SUMMARY, "Model compaction completed");
            return true;
        } catch (Exception e) {
            log.warn("Model compaction failed: executionId={}", execution.getId(), e);
            usage.publish(execution, ContextUpdateEvent.Phase.SQUEEZE_COMPLETED,
                    ContextUpdateEvent.Band.SUMMARY, "Model compaction failed");
            return false;
        }
    }

    @Override
    public ToolExecuteResult execute(ToolExecution execution) {
        String output = generate(execution.getExecutionId(), extractContext(execution.getArgs()));
        if (CompactSummaryResolver.resolve(output) == null) {
            return ToolExecuteResult.err("Compaction returned no usable summary");
        }
        return ToolExecuteResult.success(output, ToolResultType.CONTEXT_COMPACT);
    }

    /**
     * The history fed to the compact model for this round (§3.2).
     *
     * <p>First round of a trigger window ({@code summaryGeneration == 0}): the rendered session.
     * Every later round: <b>only the previous summary</b>, so the chain shrinks instead of paying for
     * the full history again. When the previous summary cannot be located the rendered session is
     * used as a safe fallback — a wrong-but-large input only costs tokens, while dropping the input
     * would summarize nothing.</p>
     *
     * @param execution the execution being compacted, read for its summary generation
     * @param messages  the session messages as they are right now
     * @return the compact-model input for this round
     */
    private String summaryInput(Execution execution, List<Message> messages) {
        if (hasPreviousSummary(execution)) {
            String previous = previousSummaryOf(messages);
            if (previous != null && !previous.isBlank()) {
                return previous;
            }
        }
        return renderConversation(messages);
    }

    /** True when the session was already rebuilt from a summary at least once. */
    private boolean hasPreviousSummary(Execution execution) {
        CompactionState state = conversationManager.compactionState(execution);
        return state != null && state.summaryGeneration() > 0;
    }

    /**
     * The summary system message the previous rebuild left at the head of the session.
     *
     * <p>A rebuilt session opens with all its system messages — leading prompt, optional protected
     * attachment, then the summary — so the last system message of that leading block is the summary
     * (§3.4 premise A). Returns {@code null} when the session carries no such block.</p>
     */
    private String previousSummaryOf(List<Message> messages) {
        String candidate = null;
        // Index 0 is the leading business prompt; it is never the summary of a previous generation.
        for (int i = 1; i < messages.size(); i++) {
            if (!(messages.get(i) instanceof SystemMessageEntity systemMessage)) {
                break;
            }
            candidate = systemMessage.getText();
        }
        return candidate;
    }

    private String generate(String executionId, String history) {
        Optional<String> attachment = contextAttachmentProvider.attachment(executionId);
        String prompt = ContextCompactionPrompt.BASE_COMPACTION_PROMPT
                + (attachment.isPresent() ? "\nPreserve the attached application state verbatim." : "");
        String payload = attachment.map(value -> history + "\n\n[PROTECTED APPLICATION STATE]\n" + value)
                .orElse(history);
        ChatResponseEntity response = chatModel.chat(ChatRequestEntity.builder().messages(List.of(
                SystemMessageEntity.builder().text(prompt).build(), UserMessageEntity.from(payload))).build());
        return response.getAiMessageEntity().text();
    }

    /** Renders the session messages into a plain-text history as the compact-model input. */
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
                appendLine(history, "[用户]", userMessage.text());
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
                appendLine(history, "[工具结果 " + toolName + "]", toolMessage.getToolOutput());
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

    private String extractContext(String args) {
        if (args == null || args.isBlank()) {
            return args;
        }
        try {
            JsonNode node = OBJECT_MAPPER.readTree(args);
            if (node.isObject() && node.has("context")) {
                JsonNode context = node.get("context");
                return context.isTextual() ? context.asText() : context.toString();
            }
            return node.toString();
        } catch (Exception ignored) {
            return args;
        }
    }
}
