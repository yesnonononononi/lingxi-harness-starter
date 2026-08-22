package com.summit.runtime;

import com.summit.harnesscore.agent.Execution;
import com.summit.harnesscore.runtime.ExecutionRuntime;
import com.summit.harnesscore.runtime.Workspace;
import com.summit.harnesscore.tool.*;
import com.summit.runtime.workspace.LocalWorkSpace;
import dev.langchain4j.agent.tool.ToolExecutionRequest;
import dev.langchain4j.agent.tool.ToolSpecification;
import dev.langchain4j.data.message.AiMessage;
import dev.langchain4j.data.message.ChatMessage;
import dev.langchain4j.data.message.ToolExecutionResultMessage;
import dev.langchain4j.model.chat.ChatModel;
import dev.langchain4j.model.chat.request.ChatRequest;
import dev.langchain4j.model.chat.response.ChatResponse;
import org.jspecify.annotations.Nullable;

import java.util.ArrayList;
import java.util.List;

/**
 * a lifestyle of running execution and managing tools
 */
public class ChatModelRuntimeProcessor implements ExecutionRuntime {
    private final ChatModel model;
    private final ToolRegistry toolRegistry;
    private final Workspace workspace;

    public ChatModelRuntimeProcessor(ChatModel chatModel, ToolRegistry toolRegistry, Workspace workspace) {
        this.model = chatModel  ;
        this.workspace = workspace;
        this.toolRegistry = toolRegistry;
    }


    @Override
    public Execution execute(Execution execution) {
        List<ChatMessage> messages = new ArrayList<>(execution.getMessages());
        ChatRequest chatRequest = buildRequest(messages);
        execution.start();
        while (true) {
            ChatResponse chatResponse = obtainChatResponse(chatRequest);
            List<ToolExecutionRequest> toolCalls = chatResponse.aiMessage().toolExecutionRequests();
            if (toolCalls.isEmpty()) {
                appendContext(messages, chatResponse, null);
                break;
            }
            List<ToolExecutionResultMessage> toolResMessages = executeTools(toolCalls, workspace);
            appendContext(messages, chatResponse, toolResMessages);
            chatRequest = buildRequest(messages);
        }
        saveMessages(messages, execution);
        execution.complete();
        return execution;
    }

    private void saveMessages(List<ChatMessage> messages, Execution execution) {
        execution.setMessages(messages);
    }

    private void appendContext(List<ChatMessage> messages, ChatResponse chatResponse,@Nullable List<ToolExecutionResultMessage> toolExecutionResultMessage) {
        AiMessage aiMessage = chatResponse.aiMessage();
        messages.add(aiMessage);
        if(toolExecutionResultMessage != null && !toolExecutionResultMessage.isEmpty())messages.addAll(toolExecutionResultMessage);
    }

    private ChatRequest buildRequest(List<ChatMessage> messages) {
        return ChatRequest.builder()
                .messages(messages)
                .toolSpecifications(toolRegistry.getTools().values().stream().toList())
                .build();
    }

    public ChatResponse obtainChatResponse(ChatRequest chatRequest) {
        return this.model.chat(chatRequest);
    }

    public List<ToolExecutionResultMessage> executeTools(List<ToolExecutionRequest> toolExecutionRequests, Workspace workspace) {
        return toolExecutionRequests.stream().map(request -> {
                    System.out.println("Executing tool: " + request.name());
                    String toolName = request.name();
                    ToolSpecification tooSpec = this.toolRegistry.getToolSpec(toolName);
                    Tool tool = this.toolRegistry.getTool(toolName);
                    ToolExecution execution = createToolExecution(request, tooSpec);
                    return tool.executor().execute(execution);
                })
                .map(result -> ToolExecutionResultMessage.toolExecutionResultMessage(result.getId(), result.getToolSpecification().name(), result.getToolOutput()))
                .toList();
    }

    private ToolExecution createToolExecution(ToolExecutionRequest request, ToolSpecification tool) {
        return ToolExecution.builder()
                .id(request.id())
                .toolSpecification(tool)
                .workspace(this.workspace)
                .args(request.arguments())
                .build();
    }
}
