package com.summit.tools.compact;


import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.summit.harnesscore.compact.ToolResultType;
import com.summit.harnesscore.tool.ToolExecuteResult;
import com.summit.harnesscore.tool.ToolExecution;
import com.summit.harnesscore.tool.ToolExecutor;

import dev.langchain4j.data.message.SystemMessage;
import dev.langchain4j.data.message.UserMessage;
import dev.langchain4j.model.chat.ChatModel;
import dev.langchain4j.model.chat.request.ChatRequest;
import dev.langchain4j.model.chat.request.ResponseFormat;
import dev.langchain4j.model.chat.response.ChatResponse;
import lombok.AllArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.jspecify.annotations.NonNull;

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
        ChatRequest request = ChatRequest.builder()
                .messages(new LinkedList<>(List.of(SystemMessage.from("""
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
                                        """),
                                UserMessage.from(context)
                        )
                        )
                )
                .build();
        ChatResponse response = this.chatModel.chat(
                request
        );
        log.info("【compact-model】 model has responded:{} thinking:{}", response.aiMessage().text(), response.aiMessage().thinking());

        return ToolExecuteResult.success(toolExecution.getId(),
                toolExecution.getToolSpecification(),
                response.aiMessage().text(),
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
