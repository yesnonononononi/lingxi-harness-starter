package com.summit.runtime;

import com.summit.harnesscore.agent.AgentRequest;
import com.summit.harnesscore.agent.Execution;
import com.summit.harnesscore.conversation.context.RuntimeContext;
import com.summit.harnesscore.runtime.ExecutionRuntime;
import com.summit.harnesscore.tool.*;
import dev.langchain4j.agent.tool.ToolExecutionRequest;
import dev.langchain4j.agent.tool.ToolSpecification;
import dev.langchain4j.data.message.AiMessage;
import dev.langchain4j.data.message.SystemMessage;
import dev.langchain4j.data.message.ToolExecutionResultMessage;
import dev.langchain4j.data.message.UserMessage;
import dev.langchain4j.model.chat.ChatModel;
import dev.langchain4j.model.chat.request.ChatRequest;
import dev.langchain4j.model.chat.response.ChatResponse;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;

/**
 * a lifestyle of running execution and managing tools
 */
public class ChatModelRuntimeProcessor implements ExecutionRuntime {
    private final Logger logger = LoggerFactory.getLogger(ChatModelRuntimeProcessor.class);
    private final RuntimeContext<ChatModel> runtimeContext;

    public ChatModelRuntimeProcessor(RuntimeContext<ChatModel> runtimeContext) {
        this.runtimeContext = runtimeContext;

    }


    @Override
    public Execution execute(Execution execution) {
        initContext(execution.getAgentRequest());
        ChatRequest chatRequest = buildRequest();
        execution.start();
        while (true) {
            ChatResponse chatResponse = obtainChatResponse(chatRequest);

            List<ToolExecutionRequest> toolCalls = chatResponse.aiMessage().toolExecutionRequests();
            if (toolCalls.isEmpty()) {
                appendContext(chatResponse, null);
                break;
            }
            List<ToolExecutionResultMessage> toolResMessages = executeTools(toolCalls);
            appendContext(chatResponse, toolResMessages);
            chatRequest = buildRequest();
        }
        save(execution);
        execution.complete();
        return execution;
    }


    private void initContext(AgentRequest agentRequest) {
        UserMessage userMessage = UserMessage.from(agentRequest.getInput());
        SystemMessage systemMessage = SystemMessage.from(getSystemMessage());
        this.runtimeContext.messages().addAll(List.of(systemMessage, userMessage));
    }


    private void save(Execution execution) {
        execution.setMessages(this.runtimeContext.messages());
        execution.setTokenUsage(this.runtimeContext.tokenUsage());
    }

    private void appendContext(ChatResponse chatResponse, @Nullable List<ToolExecutionResultMessage> toolExecutionResultMessage) {
        AiMessage aiMessage = chatResponse.aiMessage();
        this.runtimeContext.messages().add(aiMessage);
        this.runtimeContext.tokenUsage().add(chatResponse.tokenUsage());
        if (toolExecutionResultMessage != null && !toolExecutionResultMessage.isEmpty())
            this.runtimeContext.messages().addAll(toolExecutionResultMessage);
    }

    private ChatRequest buildRequest() {
        return ChatRequest.builder()
                .messages(this.runtimeContext.messages())
                .toolSpecifications(this.runtimeContext.toolRegistry()
                        .getTools()
                        .values()
                        .stream()
                        .toList()
                )
                .build();
    }

    public ChatResponse obtainChatResponse(ChatRequest chatRequest) {
        ChatResponse response = this.runtimeContext.model().chat(chatRequest);
        logger.info("【AI】:{}", response.aiMessage().text());
        return response;
    }

    public List<ToolExecutionResultMessage> executeTools(List<ToolExecutionRequest> toolExecutionRequests) {
        return toolExecutionRequests.stream().map(request -> {
                    String toolName = request.name();
                    ToolRegistry toolRegistry = this.runtimeContext.toolRegistry();

                    ToolSpecification tooSpec = toolRegistry.getToolSpec(toolName);
                    Tool tool = toolRegistry.getTool(toolName);
                    if (tool == null) {
                        return ToolExecuteResult.err(request.id(), tooSpec, "Tool not found");
                    }
                    ToolExecution execution = createToolExecution(request, tooSpec);

                    logger.info("【Tool】 model execute :{} toolName:{}",request.arguments(),toolName);

                    return tool.executor().execute(execution);
                })
                .map(result -> {
                    ToolSpecification toolSpecification = result.getToolSpecification();
                    return ToolExecutionResultMessage.toolExecutionResultMessage(result.getId(), toolSpecification == null ? "unknown tool" : toolSpecification.name(), result.getToolOutput());
                })
                .toList();
    }

    private ToolExecution createToolExecution(ToolExecutionRequest request, ToolSpecification tool) {
        return ToolExecution.builder()
                .id(request.id())
                .toolSpecification(tool)
                .workspace(this.runtimeContext.workspace())
                .args(request.arguments())
                .build();
    }


    private String getSystemMessage() {
        return String.format("""
                        current
                         operation system : %s
                         workdir: %s
                        """,
                this.runtimeContext.workspace().getOsType(),
                this.runtimeContext.workspace().getWorkDir()
        );
    }

}
