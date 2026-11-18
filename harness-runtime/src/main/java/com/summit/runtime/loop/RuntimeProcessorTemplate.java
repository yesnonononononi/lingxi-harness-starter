package com.summit.runtime.loop;

import com.summit.core.agent.Execution;
import com.summit.core.agent.ExecutionState;
import com.summit.core.conversation.event.AgentEvent;
import com.summit.core.conversation.event.ContextChangedEvent;
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
                ContextChangedEvent pendingContextEvent = this.context.getConversationManager().startConversation(
                        execution, context.getWorkspace(), context.getMcpToolScope()).orElse(null);

                // Step two: transition the execution to the STARTED state
                execution.startChecked();

                // Step three: save the snapshot of the execution
                save(execution);

                // Step four: publish the STARTED event
                notifyLifecycle(() -> this.context.getRuntimeLifeStyleManager().onStart(execution));

                // Step five: report what the context launch changed, once the snapshot carrying it
                // is durable. A context fact is what an application audits, so it must not be
                // observed before the state it describes exists.
                deliverContextChanged(pendingContextEvent);
            }

            // Step six: run the agent loop
            LoopResult result = new AgentLoopStepRunner(context).run(execution, control);

            // Step seven: transition to the closing state; the closing snapshot is saved once in finalizeWithTransaction
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

            // END: fill context state with the execution. Only the closing snapshot and the
            // control-slot release are transactional; everything the application can observe — the
            // usage broadcast, the onRunEnd interceptors, the terminal notification — is delivered
            // afterwards, in this order, and skipped entirely when the snapshot rolled back.
            Throwable throwable = RuntimeUtil.runAll(
                    () -> execution.fillContextUsage(this.context.getUsage().report(execution)),
                    () -> {
                        this.finalizeWithTransaction(execution, control);
                        this.context.getUsage().publish(execution);
                        this.notifyRunEnd(execution);
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
     * The closing snapshot and the control-slot release commit together: a run whose snapshot did
     * not land must not have released its slot.
     *
     * <p><b>The order is load-bearing and must stay {@code save → unregister}.</b> An application
     * repository decides whether a save closes the execution's bookkeeping by asking the registry
     * whether a control slot still exists; releasing the slot first makes every terminal save look
     * like a startup failure and runs the closure work twice.</p>
     *
     * <p><b>Only persistence lives in here.</b> The usage broadcast, the {@code onRunEnd}
     * interceptors and the terminal notification all run after this method returns, so none of them
     * can be observed for a run whose snapshot rolled back — the callback throws out of here and the
     * caller's following lines never run. {@code fillContextUsage} stays outside as well: usage
     * attribution is arithmetic on the live execution and has nothing to roll back, and
     * {@code unregister} removes an in-process slot that no transaction manager can restore.</p>
     *
     * <p>{@code unregister} still belongs to the same transaction as the snapshot even though an
     * in-process slot cannot be restored: the two must share one failure boundary, or a save that
     * rolls back would have already released the slot that told the application to close the run.</p>
     */
    private void finalizeWithTransaction(Execution execution, ExecutionControlSignal control) {
        this.context.getRuntimeTransactionalSupport().execute(() -> {
            this.save(execution);
            this.context.getExecutionRepository().unregister(control);
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
        context.getExecutionRepository().save(execution);
    }

    /**
     * Reports a context change after the snapshot that carries it is durable.
     *
     * <p>Goes through {@link ExecutionRepository#afterCommit} like the terminal notifications: a
     * transactional repository defers the delivery to its commit, a plain one delivers at once. A
     * {@code null} means the launch changed nothing, which is the common case and publishes
     * nothing — the previous version dispatched a null event to every listener.</p>
     */
    private void deliverContextChanged(ContextChangedEvent event) {
        if (event == null) return;
        this.context.getExecutionRepository()
                .afterCommit(() -> notifyLifecycle(() -> this.context.getContextEventPublisher().onContextChanged(event)));
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
        this.context.getExecutionRepository().afterCommit(delivery);
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
