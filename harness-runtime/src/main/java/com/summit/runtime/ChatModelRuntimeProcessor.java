package com.summit.runtime;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.summit.harnesscore.agent.Execution;
import com.summit.harnesscore.compact.ContextSummary;
import com.summit.harnesscore.tool.ToolResultType;
import com.summit.harnesscore.conversation.context.RuntimeContext;
import com.summit.harnesscore.conversation.event.*;
import com.summit.harnesscore.runtime.ExecutionRuntime;
import com.summit.harnesscore.tool.*;
import dev.langchain4j.agent.tool.ToolExecutionRequest;
import dev.langchain4j.model.chat.request.ChatRequest;
import dev.langchain4j.model.chat.response.ChatResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import com.summit.harnesscore.compact.ContextSqueezeRequest;
import java.sql.Timestamp;
import java.util.List;

/**
 * a lifestyle of running execution and managing tools
 */
public class ChatModelRuntimeProcessor implements ExecutionRuntime {
    private final Logger logger = LoggerFactory.getLogger(ChatModelRuntimeProcessor.class);
    private final RuntimeContext context;


    public ChatModelRuntimeProcessor(RuntimeContext context) {
        this.context = context;

    }


    @Override
    public Execution execute(Execution execution) {
        String executionId = execution.getId();
        ContextSqueezeRequest contextSqueezeRequest;
        context.runtimeEventPublisher().onExecutionStart(new ExecutionStartEvent(executionId));
        context.conversationManager().startConversation(execution.getAgentRequest());
        execution.start();
        int iterations = 0;
        int maxIterations = context.getMaxIterations();
        try {
            while (context.runtimeExecutionPolicy().shouldContinue(execution,this.context.conversationManager())) {
                // ensure interaction loop does not exceed max iterations
                if (++iterations > maxIterations) {
                    logger.warn("【Agent】reached max iterations {}, stop execution to avoid context explosion", maxIterations);
                    break;
                }
                // ensure context is not squeezed to death
                if ((contextSqueezeRequest = context.runtimeExecutionPolicy().shouldSqueezeContext(this.context.conversationManager(),execution)).shouldSqueeze()) {
                    context.conversationManager().squeezeContext(contextSqueezeRequest.expectTokens(),null);
                }

                // get response from model
                ChatResponse chatResponse = context.modelInvoker().invoke(buildRequest());

                logger.info("【Agent】:{} thinking:{}",chatResponse.aiMessage().text(), chatResponse.aiMessage().thinking());

                // the lifestyle of execution processing
                context.runtimeEventPublisher().onAiMessage(new AgentMessageEvent(chatResponse.aiMessage().text(),executionId));

                // if no tool calls, add the message to conversation and break the loop
                List<ToolExecutionRequest> toolCalls = chatResponse.aiMessage().toolExecutionRequests();

                // if no tool calls, add the message to conversation and break the loop
                if (toolCalls.isEmpty()) {
                    context.conversationManager().addMessage(chatResponse, null);
                    break;
                }

                // execute the tools and get the tool calls
                List<ToolExecuteResult> toolResMessages = context.toolExecutionManager().execute(new ToolExecuteCommand(toolCalls,executionId));

                // if the tool call is context compact, rebuild the context
                ToolExecuteResult requireContextCompact = toolResMessages.stream().filter(this::isContextCompactRequest).findFirst().orElse(null);
                if(requireContextCompact != null){
                    context.conversationManager().rebuildContext(resolveContextSummary(requireContextCompact));
                    continue;
                }

                // add the tool calls to the conversation
                context.conversationManager().addMessage(chatResponse, toolResMessages);
            }

            save(execution);

            execution.complete();

            context.conversationManager().endConversation();

            context.runtimeEventPublisher().onExecutionComplete(new ExecutionCompleteEvent(executionId, execution.getTokenUsage()));
            return execution;
        } catch (Exception e) {
            context.runtimeEventPublisher().onExecutionError(new ExecutionErrorEvent(e,null,executionId, new Timestamp(System.currentTimeMillis())));
            context.conversationManager().endConversation();
            execution.fail(e.getMessage());
            return execution;
        }
    }


    private void save(Execution execution) {
        execution.setMessages(this.context.conversationManager().messages());
        execution.setTokenUsage(this.context.conversationManager().tokenUsage());
    }


    private boolean isContextCompactRequest(ToolExecuteResult toolExecuteResult){
        return toolExecuteResult.getToolResultType().equals(ToolResultType.CONTEXT_COMPACT);
    }

    private ContextSummary resolveContextSummary(ToolExecuteResult toolExecuteResult)  {
        try {
            return this.context.objectMapper().readValue(toolExecuteResult.getToolOutput(), ContextSummary.class);
        }catch (JsonProcessingException jsonProcessingException){
            logger.error("【context-summary】Error occurred while resolving context summary", jsonProcessingException);
            return null;
        }
    }

    private ChatRequest buildRequest() {
        return ChatRequest.builder()
                .messages(this.context.conversationManager().messages())
                .toolSpecifications(this.context.toolExecutionManager().toolRegistry()
                        .getTools().values().stream().map(ToolDefinition::toolSpecification)
                        .toList()
                )
                .build();
    }
}
