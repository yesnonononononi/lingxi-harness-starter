package com.summit.runtime.loop;

import com.summit.core.agent.Execution;
import com.summit.core.agent.ExecutionState;
import com.summit.core.compact.ContextUsageMetric;
import com.summit.runtime.context.RuntimeContext;
import com.summit.core.runtime.ExecutionRuntime;
import com.summit.core.runtime.loop.ExecutionControlSignal;
import com.summit.core.runtime.loop.ExecutionFailureObserver;
import com.summit.core.runtime.loop.LoopResult;
import lombok.AllArgsConstructor;
import lombok.extern.slf4j.Slf4j;

import java.util.List;
import java.util.ArrayList;

/**
 * End-to-end orchestration of a single execution: publishes lifecycle events, delegates the agent loop to {@link AgentLoopStepRunner}, persists messages and token usage, and finalises per final state.
 */
@AllArgsConstructor
@Slf4j
public class RuntimeProcessorTemplate implements ExecutionRuntime {
    private final RuntimeContext context;


    @Override
    public Execution execute(Execution execution) {
        boolean resumed = execution.getExecutionState() == ExecutionState.SUSPENDED;
        ExecutionControlSignal control = this.context.getExecutionRepository()
                .register(execution.getId());
        return process(execution, control, resumed);
    }


    private Execution process(Execution execution, ExecutionControlSignal control, boolean resumed) {
        RuntimeException failure = null;
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

            // Step six: handle the result by the status of result
            switch (result.status()){
                case SUSPENDED -> {
                    execution.suspendChecked();
                    save(execution);
                    notifyLifecycle(() -> this.context.getRuntimeLifeStyleManager().onSuspend(execution));
                }
                case COMPLETED -> {
                    execution.completeChecked();
                    save(execution);
                    notifyLifecycle(() -> this.context.getRuntimeLifeStyleManager().onComplete(execution));
                }
                case CANCELLED -> {
                    execution.cancelChecked();
                    save(execution);
                    notifyLifecycle(() -> this.context.getRuntimeLifeStyleManager().onCancel(execution));
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
                    save(execution);
                    notifyFailureObservers(execution, e);
                    notifyLifecycle(() -> this.context.getRuntimeLifeStyleManager().onError(execution, e));
                }
            } catch (Exception callbackFailure) {
                if (callbackFailure != failure) failure.addSuppressed(callbackFailure);
            }
            throw failure;
        }finally {
            // END: fill context state with the execution
            execution.fillContextUsage(this.context.getUsage().report(execution));

            clear(execution, control, failure);
        }
    }


    private void clear(Execution execution, ExecutionControlSignal control, RuntimeException failure){
        RuntimeException cleanupFailure = null;

        try {
            // The message list is deliberately left mutable: this is the live domain object, and it
            // outlives the loop — RuntimeLifeStyleManager, LoopInterceptor and usage reporting all
            // observe it after the run, and a caller may still append to it before resuming
            // (ConversationManager#appendUserMessage / #appendSystemMessage). Wrapping it in
            // List.copyOf() made those append calls throw UnsupportedOperationException on any
            // execution that had already run once. Snapshot isolation is already guaranteed by
            // save(), which serialises the execution into a value copy, so no read-only view is needed.
            save(execution);
        } catch (RuntimeException e) {
            cleanupFailure = e;
        } finally {
            try {

                this.context.getExecutionRepository().unregister(control);

            } catch (RuntimeException e) {

                if (cleanupFailure == null) cleanupFailure = e;

                else if (cleanupFailure != e) cleanupFailure.addSuppressed(e);

                } finally {

                    if (context.getUsage() != null) context.getUsage().publish(execution);

                    notifyRunEnd(execution);
                }
        }
        if (cleanupFailure != null ) {

            if (failure != null) {

                if (failure != cleanupFailure) failure.addSuppressed(cleanupFailure);

            }
            else throw cleanupFailure;
        }
    }


    
    /** Terminal notification only: suspending an execution is not its end. */
    private void notifyRunEnd(Execution execution) {
        ExecutionState state = execution.getExecutionState();
        if (state != ExecutionState.COMPLETED && state != ExecutionState.CANCELLED && state != ExecutionState.FAILED) return;
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
