package com.summit.runtime.loop;

import com.summit.core.agent.Execution;
import com.summit.core.agent.ExecutionState;
import com.summit.core.runtime.ExecutionRuntime;
import com.summit.core.runtime.loop.ExecutionControlSignal;
import com.summit.core.runtime.loop.ExecutionFailureObserver;
import com.summit.core.runtime.loop.ExecutionRepository;
import com.summit.core.runtime.loop.LoopResult;
import com.summit.runtime.context.RuntimeContext;
import com.summit.runtime.util.RuntimeUtil;
import lombok.AllArgsConstructor;
import lombok.extern.slf4j.Slf4j;

import java.util.ArrayList;
import java.util.List;

/**
 * End-to-end orchestration of a single execution: publishes lifecycle events, delegates the agent loop to {@link AgentLoopStepRunner}, persists messages and token usage, and finalises per final state.
 */
@AllArgsConstructor
@Slf4j
public class RuntimeProcessorTemplate implements ExecutionRuntime {
    private final RuntimeContext context;


    @Override
    public Execution execute(Execution execution) {

        ExecutionControlSignal control = this.context.getExecutionRepository()
                .register(execution.getId());

        return process(execution, control, execution.isSuspended());
    }

    private Execution process(Execution execution, ExecutionControlSignal control, boolean resumed) {
        RuntimeException failure = null;
        Runnable pendingEvent = null;
        try {
            execution.setMessages(new ArrayList<>(execution.getMessages() == null ? List.of() : execution.getMessages()));

            save(execution);

            if (resumed) {
                execution.resumeChecked();
                save(execution);
                notifyLifecycle(() -> this.context.getRuntimeLifeStyleManager().onResume(execution));
            } else {

                // Step one: initialize the metadata of the execution (messages, tokenUsage, systemMessage)
                this.context.getConversationManager().startConversation(
                        execution, context.getWorkspace(), context.getMcpToolScope());

                // Step two: transition the execution to the STARTED state
                execution.startChecked();

                // Step three: save the snapshot of the execution
                save(execution);

                // Step four: publish the STARTED event
                notifyLifecycle(() -> this.context.getRuntimeLifeStyleManager().onStart(execution));
            }

            // Step five: run the agent loop
            LoopResult result = new AgentLoopStepRunner(context).run(execution, control);

            // Step six: transition to the closing state; the closing snapshot is saved once in finalizeWithTransaction
            switch (result.status()) {
                case SUSPENDED -> {
                    execution.suspendChecked();
                    pendingEvent = () -> this.context.getRuntimeLifeStyleManager().onSuspend(execution);
                }
                case COMPLETED -> {
                    execution.completeChecked();
                    pendingEvent = () -> this.context.getRuntimeLifeStyleManager().onComplete(execution);
                }
                case CANCELLED -> {
                    execution.cancelChecked();
                    pendingEvent = () -> this.context.getRuntimeLifeStyleManager().onCancel(execution);
                }
                default -> throw new IllegalStateException("loop returned a non-terminal result: " + result.status());
            }


            return execution;

        } catch (Exception e) {
            failure = e instanceof RuntimeException runtime ? runtime : new RuntimeException(e);
            try {
                // NOT completed / Canceled / Failed
                if (!execution.getExecutionState().isTerminal()) {
                    execution.failChecked(e.getMessage());
                    pendingEvent = () -> this.context.getRuntimeLifeStyleManager().onError(execution, e);
                    notifyFailureObservers(execution, e);
                }
            } catch (Exception callbackFailure) {
                if (callbackFailure != failure) failure.addSuppressed(callbackFailure);
            }
            throw failure;
        } finally {
            final Runnable pe = pendingEvent;
            // The message list is deliberately left mutable: this is the live domain object, and it
            // outlives the loop — RuntimeLifeStyleManager, LoopInterceptor and usage reporting all
            // observe it after the run, and a caller may still append to it before resuming
            // (ConversationManager#appendUserMessage / #appendSystemMessage). Wrapping it in
            // List.copyOf() made those append calls throw UnsupportedOperationException on any
            // execution that had already run once. Snapshot isolation is already guaranteed by
            // save(), which serializes the execution into a value copy, so no read-only view is needed.

            // END: fill context state with the execution. Usage reporting and the snapshot land in
            // one transaction; the terminal notification runs only after that transaction commits.
            Throwable throwable = RuntimeUtil.runAll(
                    () -> execution.fillContextUsage(this.context.getUsage().report(execution)),
                    () -> {
                        this.finalizeWithTransaction(execution, control);
                        this.notifyTerminal(pe);
                    }
            );

            if (throwable != null) {
                if (failure != null) {
                    if (throwable != failure) failure.addSuppressed(throwable);
                } else {
                    throw new RuntimeException(throwable);
                }
            }
        }
    }



    /**
     * The closing snapshot, the control-slot release, the usage broadcast and the terminal callback
     * commit together: a run whose snapshot did not land must not have released its slot or told the
     * application it finished.
     *
     * <p><b>The order is load-bearing and must stay {@code save → unregister}.</b> An application
     * repository decides whether a save closes the execution's bookkeeping by asking the registry
     * whether a control slot still exists; releasing the slot first makes every terminal save look
     * like a startup failure and runs the closure work twice.</p>
     *
     * <p>{@code fillContextUsage} stays outside the transaction: usage attribution is arithmetic on
     * the live execution and has nothing to roll back, and {@code unregister} removes an in-process
     * slot that no transaction manager can restore.</p>
     *
     * <p>The caller delivers the terminal notification <em>after</em> this method returns — see
     * {@link #notifyTerminal}, which is the only exit for a terminal notification precisely because
     * a callback registered inside the commit would be silently dropped.</p>
     */
    private void finalizeWithTransaction(Execution execution, ExecutionControlSignal control) {
        this.context.getRuntimeTransactionalSupport().execute(() -> {
            this.save(execution);
            this.context.getExecutionRepository().unregister(control);
            this.context.getUsage().publish(execution);
            this.notifyRunEnd(execution);
            return null;
        });
    }




    /**
     * Terminal notification only: suspending an execution is not its end.
     */
    private void notifyRunEnd(Execution execution) {
        ExecutionState state = execution.getExecutionState();
        if (state != ExecutionState.COMPLETED && state != ExecutionState.CANCELLED && state != ExecutionState.FAILED)
            return;
        try {
            context.getLoopInterceptorProcessor().onRunEnd(execution);
        } catch (Exception e) {
            log.warn("Terminal callback failed: executionId={}", execution.getId(), e);
        }
    }

    private void save(Execution execution) {
        if (context.getExecutionRepository() != null) {
            context.getExecutionRepository().save(execution);
        }
    }

    private void notifyLifecycle(Runnable notification) {
        try {
            notification.run();
        } catch (RuntimeException e) {
            log.warn("Execution lifecycle observer failed", e);
        }
    }

    /**
     * The single exit for a terminal notification (completed / cancelled / suspended / failed):
     * it leaves the runtime through {@link ExecutionRepository#afterCommit}, so a transactional
     * repository publishes it only once the surrounding snapshot is durable, and it stays the last
     * thing the application observes.
     */
    private void notifyTerminal(Runnable notification) {
        if (notification == null) return;
        Runnable delivery = () -> this.notifyLifecycle(notification);
        ExecutionRepository repository = this.context.getExecutionRepository();
        if (repository == null) {
            delivery.run();
            return;
        }
        repository.afterCommit(delivery);
    }

    private void notifyFailureObservers(Execution execution, Exception cause) {
        for (ExecutionFailureObserver observer : context.getFailureObservers()) {
            try {
                observer.onFailure(execution, cause);
            } catch (RuntimeException e) {
                log.warn("Execution failure observer failed: executionId={}", execution.getId(), e);
            }
        }
    }


}
