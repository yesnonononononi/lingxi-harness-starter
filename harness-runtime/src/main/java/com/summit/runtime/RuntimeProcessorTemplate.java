package com.summit.runtime;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.summit.harnesscore.agent.Execution;
import com.summit.harnesscore.compact.ContextSqueezeRequest;
import com.summit.harnesscore.compact.ContextSummary;
import com.summit.harnesscore.conversation.api.ChatRequestEntity;
import com.summit.harnesscore.conversation.api.ChatResponseEntity;
import com.summit.harnesscore.conversation.context.RuntimeContext;
import com.summit.harnesscore.conversation.event.AgentMessageEvent;
import com.summit.harnesscore.conversation.event.ExecutionCompleteEvent;
import com.summit.harnesscore.conversation.event.ExecutionErrorEvent;
import com.summit.harnesscore.conversation.event.ExecutionStartEvent;
import com.summit.harnesscore.conversation.message.TokenUsageEntity;
import com.summit.harnesscore.model.ModelChatCommand;
import com.summit.harnesscore.runtime.ExecutionRuntime;
import com.summit.harnesscore.tool.ToolExecuteCommand;
import com.summit.harnesscore.tool.ToolExecuteResult;
import com.summit.harnesscore.tool.ToolResultType;
import com.summit.runtime.model.StreamingModelResponseBehaveDecider;
import lombok.AllArgsConstructor;
import lombok.extern.slf4j.Slf4j;

import java.io.Serializable;
import java.sql.Timestamp;
import java.util.concurrent.CompletableFuture;

@AllArgsConstructor
@Slf4j
public class RuntimeProcessorTemplate implements ExecutionRuntime {
    private final RuntimeContext context;

    @Override
    public Execution execute(Execution execution) {
        String executionId = execution.getId();
        Serializable sessionId = execution.getSessionId();
        ContextSqueezeRequest contextSqueezeRequest;
        context.getRuntimeEventPublisher().onExecutionStart(new ExecutionStartEvent(executionId, sessionId));
        context.getConversationManager().startConversation(execution.getAgentRequest());
        execution.start();
        int iterations = 0;
        int maxIterations = context.getMaxIterations();
        try {

            while (true) {
                // ensure interaction loop does not exceed max iterations
                if (++iterations > maxIterations) {
                    log.warn("【Agent】reached max iterations {}, stop execution to avoid context explosion", maxIterations);
                    break;
                }
                contextSqueezeRequest = context.getRuntimeExecutionPolicy().shouldSqueezeContext(this.context.getConversationManager(), execution);
                if (contextSqueezeRequest.shouldSqueeze()) {
                    log.info("【context-squeeze】current context over limit, squeezing to {} tokens", contextSqueezeRequest.expectTokens());
                    context.getConversationManager().squeezeContext(contextSqueezeRequest.expectTokens(), null,sessionId);
                }

                if (!context.getRuntimeExecutionPolicy().shouldContinue(execution, this.context.getConversationManager())) {
                    log.warn("【Agent】context still over limit after squeezing, stop execution to avoid context explosion");
                    break;
                }

                // get response from model
                ChatResponseEntity chatResponse = this.context.getInvoker().invoke(buildRequest(execution));

                log.info("【Agent】:{} thinking:{}", chatResponse.getAiMessageEntity().text(), chatResponse.getAiMessageEntity().getThinking());

                // the lifestyle of execution processing
                context.getRuntimeEventPublisher().onAiMessage(new AgentMessageEvent(sessionId, chatResponse.getAiMessageEntity().text(), chatResponse.getAiMessageEntity().getThinking(), executionId));

                // if no tool calls, add the message to conversation and break the loop
                if (chatResponse.getAiMessageEntity().getToolCalls() == null || chatResponse.getAiMessageEntity().getToolCalls().isEmpty()) {
                    context.getConversationManager().addMessage(sessionId,chatResponse, null);
                    break;
                }

                var toolResMessages = context.getToolExecutionManager().execute(
                        new ToolExecuteCommand(chatResponse.getAiMessageEntity().getToolCalls(), executionId, sessionId, context.getWorkspace()));

                // if the tool call is context compact, rebuild the context
                ToolExecuteResult requireContextCompact = toolResMessages.stream().filter(this::isContextCompactRequest).findFirst().orElse(null);
                if (requireContextCompact != null) {
                    context.getConversationManager().rebuildContext(resolveContextSummary(requireContextCompact),sessionId);
                    continue;
                }

                // add the tool calls to the conversation
                context.getConversationManager().addMessage(sessionId,chatResponse, toolResMessages);
            }

            save(execution);

            execution.complete();

            context.getConversationManager().endConversation(sessionId);

            context.getRuntimeEventPublisher().onExecutionComplete(new ExecutionCompleteEvent(executionId, sessionId,
                    buildTokenInfo(execution)
            ));
            return execution;
        } catch (Exception e) {
            context.getRuntimeEventPublisher().onExecutionError(new ExecutionErrorEvent(e, null, executionId, new Timestamp(System.currentTimeMillis()), sessionId));
            context.getConversationManager().endConversation(sessionId);
            execution.fail(e.getMessage());
            return execution;
        }
    }


    private void save(Execution execution) {
        execution.setMessages(this.context.getConversationManager().messages(execution.getSessionId()));
        execution.setTokenUsage(this.context.getConversationManager().tokenUsage(execution.getSessionId()));
    }


    private boolean isContextCompactRequest(ToolExecuteResult toolExecuteResult) {
        return toolExecuteResult.getToolResultType().equals(ToolResultType.CONTEXT_COMPACT);
    }

    private ContextSummary resolveContextSummary(ToolExecuteResult toolExecuteResult) {
        try {
            return this.context.getObjectMapper().readValue(toolExecuteResult.getToolOutput(), ContextSummary.class);
        } catch (JsonProcessingException jsonProcessingException) {
            log.error("【context-summary】Error occurred while resolving context summary", jsonProcessingException);
            return null;
        }
    }

    private ModelChatCommand buildRequest(Execution execution) {
        ModelChatCommand.ModelChatCommandBuilder builder = ModelChatCommand.builder()
                .chatRequest(
                        ChatRequestEntity.builder()
                                .messages(this.context.getConversationManager().messages(execution.getSessionId()))
                                .tools(this.context.getToolExecutionManager().toolRegistry().getTools().values().stream().toList())
                                .build()
                ).thinking(execution.isThinking())
                .streaming(execution.isStreaming());

        if (execution.isStreaming()) {
            StreamingModelResponseBehaveDecider decider = new StreamingModelResponseBehaveDecider(this.context.getRuntimeEventPublisher(), StreamingModelResponseBehaveDecider.StreamingResponseContext.builder()
                    .sessionId(execution.getSessionId())
                    .executionId(execution.getId())
                    .agentId(execution.getAgentId())
                    .future(new CompletableFuture<>())
                    .build());
            builder.streamingChatResponseHandler(decider);
        }
        return builder.build();

    }

    private ExecutionCompleteEvent.TokenInfo buildTokenInfo(Execution execution) {
        TokenUsageEntity tokenUsage = execution.getTokenUsage();
        if (tokenUsage == null) {
            return null;
        }
        return ExecutionCompleteEvent.TokenInfo.builder()
                .inputTokenCount(tokenUsage.getInputTokens())
                .outputTokenCount(tokenUsage.getOutputTokens())
                .totalTokenCount(tokenUsage.getTotalTokens())
                .build();
    }
}
