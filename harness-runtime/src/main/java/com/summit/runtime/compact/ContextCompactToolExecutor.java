package com.summit.runtime.compact;


import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.summit.core.compact.ContextCompactionPrompt;
import com.summit.core.compact.ContextAttachmentProvider;
import com.summit.core.conversation.api.ChatRequestEntity;
import com.summit.core.conversation.api.ChatResponseEntity;
import com.summit.core.conversation.message.Message;
import com.summit.core.conversation.message.SystemMessageEntity;
import com.summit.core.conversation.message.UserMessageEntity;
import com.summit.core.model.chat.ChatModel;
import com.summit.core.tool.ToolResultType;
import com.summit.core.tool.ToolExecuteResult;
import com.summit.core.tool.ToolExecution;
import com.summit.core.tool.ToolExecutor;


import lombok.AllArgsConstructor;
import lombok.NonNull;
import lombok.extern.slf4j.Slf4j;


import java.util.LinkedList;
import java.util.List;
import java.util.Optional;

@Slf4j
@AllArgsConstructor
public class ContextCompactToolExecutor implements ToolExecutor {
    private final ChatModel chatModel;
    private final ContextAttachmentProvider contextAttachmentProvider;
    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();

    @Override
    public @NonNull ToolExecuteResult execute(ToolExecution toolExecution) {
        String context = extractContext(toolExecution.getArgs());
        Optional<String> protectedContext = contextAttachmentProvider.attachment(toolExecution.getExecutionId());

        StringBuilder systemPrompt = new StringBuilder(ContextCompactionPrompt.BASE_COMPACTION_PROMPT);
        protectedContext.ifPresent(value -> systemPrompt.append("\nPreserve the attached application state verbatim."));

        List<Message> messages = new LinkedList<>();
        messages.add(SystemMessageEntity.builder().text(systemPrompt.toString()).build());
        String payload = protectedContext
                .map(value -> context + "\n\n[PROTECTED APPLICATION STATE]\n" + value)
                .orElse(context);
        messages.add(UserMessageEntity.from(payload));
        ChatRequestEntity request = ChatRequestEntity.builder()
                .messages(messages)
                .build();
        ChatResponseEntity response = this.chatModel.chat(
                request
        );
        log.info("【compact-model】 model has responded:{} thinking:{}", response.getAiMessageEntity().text(), response.getAiMessageEntity().getThinking());

        return ToolExecuteResult.success(
                response.getAiMessageEntity().text(),
                ToolResultType.CONTEXT_COMPACT
        );
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
