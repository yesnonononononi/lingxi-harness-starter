package com.summit.runtime.loop.lifeStyle;

import com.summit.core.agent.Execution;
import com.summit.core.conversation.event.*;
import com.summit.core.runtime.loop.lifestyle.RuntimeLifeStyleManager;
import lombok.AllArgsConstructor;
import lombok.extern.slf4j.Slf4j;

@Slf4j
@AllArgsConstructor
public class DefaultRuntimeLifeStyleManager implements RuntimeLifeStyleManager {
    private final RuntimeEventPublisher runtimeEventPublisher;

    @Override
    public void onStart(Execution execution) {
        this.runtimeEventPublisher.onExecutionStart(new ExecutionStartEvent(execution.getId(), execution.eventMetaData()));
    }

    @Override
    public void onCancel(Execution execution) {
        log.warn("【agent-loop】process is cancelled: {}", execution.getId());
        this.runtimeEventPublisher.onExecutionCancelled(
                new ExecutionCancelledEvent(execution.getId(), TokenInfo.from(execution.getTokenUsage()),
                        execution.eventMetaData()));
    }

    @Override
    public void onSuspend(Execution execution) {
        log.warn("【agent-loop】process is suspended: {}", execution.getId());
        this.runtimeEventPublisher.onExecutionSuspended(new ExecutionSuspendedEvent(execution.getId(), execution.eventMetaData()));
    }

    @Override
    public void onComplete(Execution execution) {
        this.runtimeEventPublisher.onExecutionComplete(
                new ExecutionCompleteEvent(execution.getId(), TokenInfo.from(execution.getTokenUsage()),
                        execution.eventMetaData()));
    }

    @Override
    public void onError(Execution execution, Exception e) {
        log.error("【agent-loop】process error: {}", execution.getId(), e);
        this.runtimeEventPublisher.onExecutionError(
                new ExecutionErrorEvent(e.getMessage(), null, execution.getId(),
                        TokenInfo.from(execution.getTokenUsage()), execution.eventMetaData()));
    }

    @Override
    public void onResume(Execution snapshot) {
        log.info("【agent-loop】process is resumed: {}", snapshot.getId());
        this.runtimeEventPublisher.onExecutionResumed(new ExecutionResumedEvent(snapshot.getId(), snapshot.eventMetaData()));
    }
}
