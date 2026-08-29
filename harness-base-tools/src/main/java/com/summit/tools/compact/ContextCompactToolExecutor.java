package com.summit.tools.compact;


import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.summit.harnesscore.conversation.api.ChatRequestEntity;
import com.summit.harnesscore.conversation.api.ChatResponseEntity;
import com.summit.harnesscore.conversation.message.Message;
import com.summit.harnesscore.conversation.message.SystemMessageEntity;
import com.summit.harnesscore.conversation.message.UserMessageEntity;
import com.summit.harnesscore.model.ChatModel;
import com.summit.harnesscore.tool.ToolResultType;
import com.summit.harnesscore.tool.ToolExecuteResult;
import com.summit.harnesscore.tool.ToolExecution;
import com.summit.harnesscore.tool.ToolExecutor;


import lombok.AllArgsConstructor;
import lombok.NonNull;
import lombok.extern.slf4j.Slf4j;


import java.util.LinkedList;
import java.util.List;

@Slf4j
@AllArgsConstructor
public class ContextCompactToolExecutor implements ToolExecutor {
    private final ChatModel chatModel;
    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();

    @Override
    public @NonNull ToolExecuteResult execute(ToolExecution toolExecution) {
        String context = extractContext(toolExecution.getArgs());
        List<Message> messages = new LinkedList<>();
        messages.add(SystemMessageEntity.builder().text("""
                                                       You are a context compression assistant. Compress the given conversation history into a concise but complete summary so the conversation can be continued seamlessly.
                                                       Keep all important facts, decisions, user requirements, tool calls and their results, and the current task state.
                                                       Write the summary in the same language as the conversation. Output only the summary text, without any extra explanation.
                                        response format: JSON
                                         {
                                         "goal" : "the goal of the conversation",
                                         "summary": "summary of context",
                                         "completed[]" : "list of completed tasks",
                                         "pending[]" : "list of pending tasks",
                                         "state": "current state of the conversation . enum DONE or FAILED"
                                         }
                                        """).build());
        messages.add(UserMessageEntity.from(context));
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
