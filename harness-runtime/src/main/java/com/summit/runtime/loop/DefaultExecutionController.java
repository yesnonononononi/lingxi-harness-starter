package com.summit.runtime.loop;


import com.summit.core.agent.Agent;
import com.summit.core.agent.Execution;
import com.summit.core.agent.ExecutionState;
import com.summit.core.conversation.event.ExecutionCancelledEvent;
import com.summit.core.conversation.event.RuntimeEventPublisher;
import com.summit.core.conversation.event.TokenInfo;
import com.summit.core.runtime.loop.ApprovalOutcome;
import com.summit.core.runtime.loop.ExecutionControl;
import com.summit.core.runtime.loop.ExecutionRepository;
import com.summit.core.runtime.loop.lifestyle.RuntimeLifeStyleManager;
import com.summit.core.transaction.RuntimeTransactionalSupport;
import lombok.extern.slf4j.Slf4j;

import java.util.Objects;
import java.util.function.Supplier;

@Slf4j
public class DefaultExecutionController implements ExecutionControl {
    private final Supplier<? extends Agent> agent;
    private final ExecutionRepository executionRepository;
    private final RuntimeEventPublisher runtimeEvents;
    private final RuntimeLifeStyleManager runtimeLifeStyleManager;
    private final RuntimeTransactionalSupport transactionalSupport;

    public DefaultExecutionController(Supplier<? extends Agent> agent,
                                      ExecutionRepository executionRepository,
                                      RuntimeEventPublisher runtimeEvents,
                                      RuntimeLifeStyleManager runtimeLifeStyleManager) {
        this(agent, executionRepository, runtimeEvents, runtimeLifeStyleManager, RuntimeTransactionalSupport.direct());
    }

    public DefaultExecutionController(Supplier<? extends Agent> agent,
                                      ExecutionRepository executionRepository,
                                      RuntimeEventPublisher runtimeEvents,
                                      RuntimeLifeStyleManager runtimeLifeStyleManager,
                                      RuntimeTransactionalSupport transactionalSupport) {
        this.agent = agent;
        this.executionRepository = executionRepository;
        this.runtimeEvents = runtimeEvents;
        this.runtimeLifeStyleManager = runtimeLifeStyleManager;
        this.transactionalSupport = transactionalSupport == null ? RuntimeTransactionalSupport.direct() : transactionalSupport;
    }


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
        Objects.requireNonNull(execution, "execution");
        Objects.requireNonNull(execution.getId(), "execution.id");
        Objects.requireNonNull(execution.getExecutionState(), "execution.executionState");


        if (execution.getExecutionState() != ExecutionState.SUSPENDED) {
            throw new IllegalStateException("Only a suspended execution can be resumed: " + execution.getId());
        }

        return agent.get().execute(execution);

    }

    @Override
    public void beginApproval(Execution execution) {
        transactionalSupport.execute(() -> {
            execution.resumeChecked();
            executionRepository.save(execution);
            return null;
        });
    }

    @Override
    public void finishApproval(Execution execution, ApprovalOutcome outcome) {
        Objects.requireNonNull(outcome, "outcome");
        transactionalSupport.execute(() -> {
            if (outcome == ApprovalOutcome.CANCELLED) {
                execution.cancelChecked();
            } else {
                execution.suspendChecked();
            }
            executionRepository.save(execution);
            if (outcome == ApprovalOutcome.CANCELLED) {
                String executionId = execution.getId();
                TokenInfo tokenInfo = TokenInfo.from(execution.getTokenUsage());
                executionRepository.afterCommit(() -> {
                    // inside a commit callback: one throwing observer must not abort the others or the commit
                    bestEffort(() -> runtimeEvents.onExecutionCancelled(new ExecutionCancelledEvent(executionId,
                            tokenInfo, execution.eventMetaData()))).run();
                    bestEffort(() -> runtimeLifeStyleManager.recordTokenAudit(execution)).run();
                });
            }
            return null;
        });
    }

    @Override
    public void failApproval(Execution execution, String errorMessage) {
        fail(execution, new IllegalStateException(errorMessage)).run();
    }

    @Override
    public Runnable fail(Execution execution, Exception cause) {
        Objects.requireNonNull(cause, "cause");
        transactionalSupport.execute(() -> {
            execution.failChecked(cause.getMessage());
            executionRepository.save(execution);
            return null;
        });
        Runnable notification = bestEffort(() -> runtimeLifeStyleManager.onError(execution, cause));
        return () -> executionRepository.afterCommit(notification);
    }

    /**
     * Observer failures never change the outcome and never escape a commit callback: other
     * synchronizations and the delivery of the remaining terminal notifications still have to run.
     */
    private static Runnable bestEffort(Runnable notification) {
        return () -> {
            try {
                notification.run();
            } catch (RuntimeException e) {
                log.warn("Execution lifecycle observer failed", e);
            }
        };
    }
}
