package com.summit.runtime.lifeStyle;

import com.summit.core.agent.Execution;
import com.summit.core.conversation.ConversationManager;
import com.summit.core.conversation.event.*;
import com.summit.core.conversation.message.TokenUsageEntity;
import com.summit.core.runtime.RuntimeLifeStyleManager;
import lombok.AllArgsConstructor;
import lombok.extern.slf4j.Slf4j;

import java.io.Serializable;
import java.sql.Timestamp;

@Slf4j
@AllArgsConstructor
public class DefaultRuntimeLifeStyleManager implements RuntimeLifeStyleManager {
    private final RuntimeEventPublisher runtimeEventPublisher;
    private final ConversationManager conversationManager;



    @Override
    public void onStart(Execution execution) {
        this.runtimeEventPublisher.onExecutionStart(new ExecutionStartEvent(execution.getId(), execution.getSessionId()));
        this.conversationManager.startConversation(execution.getAgentRequest());
        execution.start();
    }

    @Override
    public void onCancel(Execution execution) {
        Serializable sessionId = execution.getSessionId();
        log.warn("【agent-loop】process is cancelled: {}", execution.getId());
        this.conversationManager.endConversation(sessionId);
        this.runtimeEventPublisher.onExecutionCancelled(new ExecutionCancelledEvent(execution.getId(), sessionId));
    }

    @Override
    public void onComplete(Execution execution) {
        Serializable sessionId = execution.getSessionId();

        execution.complete();

        this.conversationManager.endConversation(sessionId);

        this.runtimeEventPublisher.onExecutionComplete(
                new ExecutionCompleteEvent(execution.getId(), sessionId, buildTokenInfo(execution)));

    }

    @Override
    public void onError(Execution execution, Exception e) {
        Serializable sessionId = execution.getSessionId();
        this.runtimeEventPublisher.onExecutionError(
                new ExecutionErrorEvent(e, null, execution.getId(), new Timestamp(System.currentTimeMillis()), sessionId));
        this.conversationManager.endConversation(sessionId);
        execution.fail(e.getMessage());
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
