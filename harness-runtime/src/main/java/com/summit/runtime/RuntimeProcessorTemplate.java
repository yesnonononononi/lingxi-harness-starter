package com.summit.runtime;

import com.summit.core.agent.Execution;
import com.summit.core.agent.ExecutionState;
import com.summit.core.compact.CompactSummaryResolver;
import com.summit.core.compact.ContextSummary;
import com.summit.core.conversation.api.ChatRequestEntity;
import com.summit.core.conversation.api.ChatResponseEntity;
import com.summit.core.conversation.context.RuntimeContext;
import com.summit.core.conversation.event.AgentMessageEvent;
import com.summit.core.conversation.event.ExecutionCancelledEvent;
import com.summit.core.conversation.event.ExecutionCompleteEvent;
import com.summit.core.conversation.event.ExecutionErrorEvent;
import com.summit.core.conversation.event.ExecutionStartEvent;
import com.summit.core.conversation.message.TokenUsageEntity;
import com.summit.core.model.ModelChatCommand;
import com.summit.core.runtime.ExecutionRuntime;
import com.summit.core.runtime.LifeStyleCommandRegistry;
import com.summit.core.runtime.LifeStyleCommandStore;
import com.summit.core.tool.ToolExecuteCommand;
import com.summit.core.tool.ToolExecuteResult;
import com.summit.core.tool.ToolResultType;
import com.summit.runtime.model.StreamingModelResponseBehaveDecider;
import lombok.AllArgsConstructor;
import lombok.extern.slf4j.Slf4j;

import java.io.Serializable;
import java.sql.Timestamp;
import java.util.List;
import java.util.concurrent.CompletableFuture;

@AllArgsConstructor
@Slf4j
public class RuntimeProcessorTemplate implements ExecutionRuntime {
    private final RuntimeContext context;

    @Override
    public Execution execute(Execution execution) {
        String executionId = execution.getId();
        Serializable sessionId = execution.getSessionId();

        context.getRuntimeEventPublisher().onExecutionStart(new ExecutionStartEvent(executionId, sessionId));
        context.getConversationManager().startConversation(execution.getAgentRequest());

        execution.start();

        try {

            while (true) {
                if(!context.getCheckPointer().beforeCheckpoint(execution)){
                    log.warn("【agent-loop】 process is stopped due to notConforming condition: {}", executionId);
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

                List<ToolExecuteResult> toolResMessages = context.getToolExecutionManager().execute(
                        new ToolExecuteCommand(chatResponse.getAiMessageEntity().getToolCalls(), executionId, sessionId, context.getWorkspace()));

                // if the tool call is context compact, rebuild the context
                ToolExecuteResult requireContextCompact = toolResMessages.stream().filter(this::isContextCompactRequest).findFirst().orElse(null);
                if (requireContextCompact != null) {
                    context.getConversationManager().rebuildContext(resolveContextSummary(requireContextCompact),sessionId);
                    continue;
                }

                // add the tool calls to the conversation
                context.getConversationManager().addMessage(sessionId,chatResponse, toolResMessages);

                if (!context.getCheckPointer().afterCheckpoint(execution)) {
                    log.warn("【agent-loop】 process is stopped due to lifestyle changed: {}", executionId);
                    break;
                }
            }

            save(execution);


            if (execution.getExecutionState() == ExecutionState.CANCELLED) {
                log.warn("【agent-loop】 process is cancelled: {}", executionId);
                context.getConversationManager().endConversation(sessionId);
                context.getRuntimeEventPublisher().onExecutionCancelled(new ExecutionCancelledEvent(executionId, sessionId));
                return execution;
            }

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
        } finally {
            releaseCommandStore(sessionId);
        }
    }

    /**
     * Releases the per-execution command store bound by the runtime factory:
     * unregisters it from the per-session registry (identity-guarded, so a newer
     * store registered by a later execution of the same session is untouched)
     * and clears its command queue.
     */
    private void releaseCommandStore(Serializable sessionId) {
        LifeStyleCommandStore store = context.getLifeStyleCommandStore();
        LifeStyleCommandRegistry registry = context.getLifeStyleCommandRegistry();
        if (store != null && registry != null) {
            registry.unregister(sessionId, store);
            store.destroy();
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
            
            ContextSummary summary = CompactSummaryResolver.resolve(toolExecuteResult.getToolOutput());
            if (summary == null) {
                log.warn("【context-summary】compact model returned no usable summary, context rebuild is skipped");
            }
            return summary;
        } catch (Exception unexpected) {
            log.error("【context-summary】Unexpected error occurred while resolving context summary", unexpected);
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
