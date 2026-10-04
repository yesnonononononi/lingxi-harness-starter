package com.summit.runtime.loop;


import com.summit.core.agent.Agent;
import com.summit.core.agent.Execution;
import com.summit.core.agent.ExecutionState;
import com.summit.core.conversation.event.ExecutionCancelledEvent;
import com.summit.core.conversation.event.ExecutionErrorEvent;
import com.summit.core.conversation.event.RuntimeEventPublisher;
import com.summit.core.conversation.event.TokenInfo;
import com.summit.core.runtime.loop.ExecutionRepository;
import com.summit.core.runtime.loop.ExecutionControl;
import com.summit.core.runtime.loop.ApprovalOutcome;
import lombok.AllArgsConstructor;

import java.util.Objects;

@AllArgsConstructor
public class DefaultExecutionController implements ExecutionControl {
    private final Agent agent;
    private final ExecutionRepository executionRepository;
    private final RuntimeEventPublisher runtimeEvents;


    @Override
    public void cancel(String executionId) {
        executionRepository.requireCancel(executionId);
    }

    @Override
    public void suspend(String executionId) {
        executionRepository.requireSuspend(executionId);
    }

    @Override
    public Execution resume(Execution execution) {
        Objects.requireNonNull(execution,"execution");
        Objects.requireNonNull(execution.getId(), "execution.id");
        Objects.requireNonNull(execution.getExecutionState(), "execution.executionState");
        if (execution.getExecutionState() != ExecutionState.SUSPENDED) {
            throw new IllegalStateException("Only a suspended execution can be resumed: " + execution.getId());
        }
        return agent.execute(execution);
    }

    @Override
    public void beginApproval(Execution execution) {
        execution.resumeChecked();
        executionRepository.save(execution);
    }

    @Override
    public void finishApproval(Execution execution, ApprovalOutcome outcome) {
        Objects.requireNonNull(outcome, "outcome");
        if (outcome == ApprovalOutcome.CANCELLED) {
            execution.cancelChecked();
        } else {
            execution.suspendChecked();
        }
        executionRepository.save(execution);
        if (outcome == ApprovalOutcome.CANCELLED) {
            String executionId = execution.getId();
            TokenInfo tokenInfo = TokenInfo.from(execution.getTokenUsage());
            executionRepository.afterCommit(() -> runtimeEvents.onExecutionCancelled(
                    new ExecutionCancelledEvent(executionId, tokenInfo,
                            execution.eventMetaData())));
        }
    }

    @Override
    public void failApproval(Execution execution, String errorMessage) {
        execution.failChecked(errorMessage);
        executionRepository.save(execution);
        String executionId = execution.getId();
        TokenInfo tokenInfo = TokenInfo.from(execution.getTokenUsage());
        executionRepository.afterCommit(() -> runtimeEvents.onExecutionError(
                new ExecutionErrorEvent(errorMessage,
                        null,
                        executionId,
                        tokenInfo,
                        execution.eventMetaData()
                )));
    }
}
