package com.summit.runtime;

import com.summit.core.agent.Execution;
import com.summit.core.agent.ExecutionState;
import com.summit.core.conversation.context.RuntimeContext;
import com.summit.core.conversation.event.ExecutionCancelledEvent;
import com.summit.core.conversation.event.ExecutionCompleteEvent;
import com.summit.core.conversation.event.ExecutionErrorEvent;
import com.summit.core.conversation.event.ExecutionStartEvent;
import com.summit.core.conversation.message.TokenUsageEntity;
import com.summit.core.plan.PlanStepStatus;
import com.summit.core.runtime.ExecutionRuntime;
import com.summit.core.runtime.LifeStyleCommandRegistry;
import com.summit.core.runtime.LifeStyleCommandStore;
import lombok.AllArgsConstructor;
import lombok.extern.slf4j.Slf4j;

import java.io.Serializable;
import java.sql.Timestamp;

/**
 * End-to-end orchestration of a single execution: publishes lifecycle events, delegates the agent loop
 * to {@link AgentLoopRunner}, persists messages and token usage, and finalises per final state. The
 * per-execution command store (created by the factory) is released here.
 */
@AllArgsConstructor
@Slf4j
public class RuntimeProcessorTemplate implements ExecutionRuntime {
    private final RuntimeContext context;

    @Override
    public Execution execute(Execution execution) {
        Serializable sessionId = execution.getSessionId();
        context.getRuntimeEventPublisher().onExecutionStart(new ExecutionStartEvent(execution.getId(), sessionId));
        context.getConversationManager().startConversation(execution.getAgentRequest());
        execution.start();

        try {
            AgentLoopRunner agentLoop = new AgentLoopRunner(context);
            agentLoop.run(execution, sessionId);

            save(execution);
            if (execution.getExecutionState() == ExecutionState.CANCELLED) {
                log.warn("【agent-loop】process is cancelled: {}", execution.getId());
                context.getConversationManager().endConversation(sessionId);
                context.getRuntimeEventPublisher().onExecutionCancelled(new ExecutionCancelledEvent(execution.getId(), sessionId));
                return execution;
            }
            markCompletedIfPlanImplemented(execution, sessionId, agentLoop);

            execution.complete();
            context.getConversationManager().endConversation(sessionId);
            context.getRuntimeEventPublisher().onExecutionComplete(
                    new ExecutionCompleteEvent(execution.getId(), sessionId, buildTokenInfo(execution)));
            return execution;
        } catch (Exception e) {
            context.getRuntimeEventPublisher().onExecutionError(
                    new ExecutionErrorEvent(e, null, execution.getId(), new Timestamp(System.currentTimeMillis()), sessionId));
            context.getConversationManager().endConversation(sessionId);
            execution.fail(e.getMessage());
            return execution;
        } finally {
            releaseCommandStore(sessionId);
        }
    }

    /**
     * When the plan really ran write tools and the execution closed with plain text, the plan is deemed
     * implemented: mark every step of the session plan as COMPLETED.
     */
    private void markCompletedIfPlanImplemented(Execution execution, Serializable sessionId, AgentLoopRunner agentLoop) {
        if (agentLoop.isExecutedWriteSuccessfully() && agentLoop.isClosedByPlainText()
                && context.getConversationManager().planOf(sessionId).isPresent()) {
            context.getConversationManager().updatePlanSteps(sessionId, PlanStepStatus.COMPLETED);
            log.info("【agent-loop】plan steps marked COMPLETED: executionId={}", execution.getId());
        }
    }

    /**
     * Releases the command store bound to this execution: unregisters it from the per-session registry
     * (identity-protected, so a newer store registered later for the same session is untouched) and
     * clears its command queue.
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
