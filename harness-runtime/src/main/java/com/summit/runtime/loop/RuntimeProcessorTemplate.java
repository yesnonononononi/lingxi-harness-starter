package com.summit.runtime.loop;

import com.summit.core.agent.Execution;
import com.summit.core.agent.ExecutionState;
import com.summit.core.conversation.context.RuntimeContext;
import com.summit.core.runtime.ExecutionRuntime;
import com.summit.core.runtime.loop.ExecutionControlSignal;
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
                this.context.getRuntimeLifeStyleManager().onResume(execution);
            } else {
                // The scope travels with the call: the conversation manager is a singleton, so the
                // request's remote tools reach the prompt only this way.
                this.context.getConversationManager().startConversation(
                        execution, context.getWorkspace(), context.getMcpToolScope());
                this.context.getRuntimeLifeStyleManager().onStart(execution);
            }
            save(execution);

            LoopResult result = new AgentLoopStepRunner(context).run(execution, control);

            if (result.status() == LoopResult.Status.SUSPENDED) {

                this.context.getRuntimeLifeStyleManager().onSuspend(execution);

            } else {
                if (result.status() == LoopResult.Status.COMPLETED) {

                    this.context.getRuntimeLifeStyleManager().onComplete(execution);

                } else if (result.status() == LoopResult.Status.CANCELLED) {

                    this.context.getRuntimeLifeStyleManager().onCancel(execution);

                } else {
                    throw new IllegalStateException("loop returned a non-terminal result: " + result.status());
                }

            }

            return execution;

        } catch (Exception e) {
            failure = e instanceof RuntimeException runtime ? runtime : new RuntimeException(e);
            try {
                this.context.getRuntimeLifeStyleManager().onError(execution, e);
            } catch (Exception callbackFailure) {
                if (callbackFailure != failure) failure.addSuppressed(callbackFailure);
            }
            throw failure;
        }finally {
            clear(execution, control, failure);
        }
    }


    private void clear(Execution execution, ExecutionControlSignal control, RuntimeException failure){
        RuntimeException cleanupFailure = null;

        try {
            if (execution.getMessages() != null) {
                execution.setMessages(List.copyOf(execution.getMessages()));
            }
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
        if (cleanupFailure != null) {
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
            context.getLoopInterceptor().onRunEnd(execution);
        } catch (Exception e) {
            log.warn("Terminal callback failed: executionId={}", execution.getId(), e);
        }
    }

    private void save(Execution execution) {
        if (context.getExecutionRepository() != null) {
            context.getExecutionRepository().save(execution);
        }
    }


}
