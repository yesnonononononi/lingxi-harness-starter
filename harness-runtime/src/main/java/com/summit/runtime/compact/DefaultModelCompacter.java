package com.summit.runtime.compact;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.summit.core.compact.*;
import com.summit.core.conversation.ConversationManager;
import com.summit.core.conversation.api.*;
import com.summit.core.conversation.message.*;
import com.summit.core.conversation.event.ContextUpdateEvent;
import com.summit.core.model.chat.ChatModel;
import com.summit.core.runtime.loop.ContextUsageReporter;
import com.summit.core.tool.*;
import lombok.extern.slf4j.Slf4j;
import java.util.*;

/** Both automatic and tool-requested model compaction use the same generation pipeline. */
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
        usage.publish(execution, ContextUpdateEvent.Phase.SQUEEZE_STARTED, "Model compaction started");
        try {
            if (!summaryApplier.apply(
                    generate(execution.getId(), renderConversation(messages)), execution, false)) return false;
            usage.publish(execution, ContextUpdateEvent.Phase.SQUEEZE_COMPLETED, "Model compaction completed");
            return true;
        } catch (Exception e) {
            log.warn("Model compaction failed: executionId={}", execution.getId(), e);
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
