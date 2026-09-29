package com.summit.runtime.loop.lifeStyle;

import com.summit.core.agent.Execution;
import com.summit.core.conversation.event.*;
import com.summit.core.conversation.message.TokenUsageEntity;
import com.summit.core.runtime.loop.lifestyle.RuntimeLifeStyleManager;
import lombok.AllArgsConstructor;
import lombok.extern.slf4j.Slf4j;

@Slf4j
@AllArgsConstructor
public class DefaultRuntimeLifeStyleManager implements RuntimeLifeStyleManager {
    private final RuntimeEventPublisher runtimeEventPublisher;

    @Override
    public void onStart(Execution execution) {
        execution.start();
        this.runtimeEventPublisher.onExecutionStart(new ExecutionStartEvent(execution.getId()));
    }

    @Override
    public void onCancel(Execution execution) {
        execution.cancel();
        log.warn("【agent-loop】process is cancelled: {}", execution.getId());
        this.runtimeEventPublisher.onExecutionCancelled(new ExecutionCancelledEvent(execution.getId()));
    }

    @Override
    public void onSuspend(Execution execution) {
        execution.suspended();
        log.warn("【agent-loop】process is suspended: {}", execution.getId());
        this.runtimeEventPublisher.onExecutionSuspended(new ExecutionSuspendedEvent(execution.getId()));
    }

    @Override
    public void onComplete(Execution execution) {
        execution.complete();
        this.runtimeEventPublisher.onExecutionComplete(
                new ExecutionCompleteEvent(execution.getId(), buildTokenInfo(execution)));
    }

    @Override
    public void onError(Execution execution, Exception e) {
        log.error("【agent-loop】process error: {}", execution.getId(), e);
        execution.fail(e.getMessage());
        this.runtimeEventPublisher.onExecutionError(
                new ExecutionErrorEvent(e.getMessage(), null, execution.getId()));
    }

    @Override
    public void onResume(Execution snapshot) {
        snapshot.resume();
        log.info("【agent-loop】process is resumed: {}", snapshot.getId());
        this.runtimeEventPublisher.onExecutionResumed(new ExecutionResumedEvent(snapshot.getId()));
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
