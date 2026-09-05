package com.summit.tools.compact;


import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.summit.core.compact.ContextCompactionPrompt;
import com.summit.core.conversation.api.ChatRequestEntity;
import com.summit.core.conversation.api.ChatResponseEntity;
import com.summit.core.conversation.message.Message;
import com.summit.core.conversation.message.SystemMessageEntity;
import com.summit.core.conversation.message.UserMessageEntity;
import com.summit.core.model.ChatModel;
import com.summit.core.plan.PlanEntity;
import com.summit.core.plan.PlanStore;
import com.summit.core.tool.ToolResultType;
import com.summit.core.tool.ToolExecuteResult;
import com.summit.core.tool.ToolExecution;
import com.summit.core.tool.ToolExecutor;


import lombok.AllArgsConstructor;
import lombok.NonNull;
import lombok.extern.slf4j.Slf4j;


import java.io.Serializable;
import java.util.LinkedList;
import java.util.List;
import java.util.Optional;

@Slf4j
@AllArgsConstructor
public class ContextCompactToolExecutor implements ToolExecutor {
    private final ChatModel chatModel;
    private final PlanStore planStore;
    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();

    @Override
    public @NonNull ToolExecuteResult execute(ToolExecution toolExecution) {
        String context = extractContext(toolExecution.getArgs());
        Optional<PlanEntity> sessionPlan = planOf(toolExecution.getSessionId());

        StringBuilder systemPrompt = new StringBuilder(ContextCompactionPrompt.BASE_COMPACTION_PROMPT);
        sessionPlan.ifPresent(plan -> systemPrompt.append("\n").append(ContextCompactionPrompt.PLAN_PROTECTION_PROMPT));

        List<Message> messages = new LinkedList<>();
        messages.add(SystemMessageEntity.builder().text(systemPrompt.toString()).build());
        // The raw plan is attached to the compression input so it never gets lost,
        // even when the main model truncated the tool args.
        String payload = sessionPlan
                .map(plan -> context + ContextCompactionPrompt.PROTECTED_PLAN_MARKER + plan.text())
                .orElse(context);
        messages.add(UserMessageEntity.from(payload));
        ChatRequestEntity request = ChatRequestEntity.builder()
                .messages(messages)
                .build();
        ChatResponseEntity response = this.chatModel.chat(
                request
        );
        log.info("【compact-model】 model has responded:{} thinking:{}", response.getAiMessageEntity().text(), response.getAiMessageEntity().getThinking());

        return ToolExecuteResult.success(toolExecution.getId(),
                toolExecution.getToolDefinition(),
                response.getAiMessageEntity().text(),
                ToolResultType.CONTEXT_COMPACT
        );
    }

    private Optional<PlanEntity> planOf(Serializable sessionId) {
        if (sessionId == null) {
            return Optional.empty();
        }
        return this.planStore.findBySession(sessionId);
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
