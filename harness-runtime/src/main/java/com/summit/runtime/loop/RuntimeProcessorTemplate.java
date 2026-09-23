package com.summit.runtime.loop;

import com.summit.core.agent.Execution;
import com.summit.core.agent.ExecutionState;
import com.summit.core.conversation.context.RuntimeContext;
import com.summit.core.runtime.ExecutionRuntime;
import com.summit.core.runtime.loop.ExecutionControlSignal;
import com.summit.core.runtime.loop.LoopExecutionOutcome;
import com.summit.core.runtime.loop.LoopResult;
import lombok.AllArgsConstructor;
import lombok.extern.slf4j.Slf4j;

import java.util.List;

/**
 * End-to-end orchestration of a single execution: publishes lifecycle events, delegates the agent loop to {@link AgentLoopStepRunner}, persists messages and token usage, and finalises per final state.
 */
@AllArgsConstructor
@Slf4j
public class RuntimeProcessorTemplate implements ExecutionRuntime {
    private static final String SUSPENDED_CONTEXT_MESSAGE = """
            The previous execution was suspended before completion. Continue from the committed
            conversation context when execution resumes; do not assume the task is complete.
            """;

    private final RuntimeContext context;


    @Override
    public Execution execute(Execution execution) {
        boolean resumed = execution.getExecutionState() == ExecutionState.SUSPENDED;
        // Persist identity and the resume checkpoint before registering or publishing start events.
        save(execution);
        ExecutionControlSignal control = this.context.getActiveExecutionRegistry()
                .register(execution.getId());
        return process(execution, control, resumed);
    }


    private Execution process(Execution execution, ExecutionControlSignal control, boolean resumed) {
        try {
            if (resumed) {
                this.context.getRuntimeLifeStyleManager().onResume(execution);
            } else {
                this.context.getConversationManager().startConversation(execution, context.getWorkspace());
                this.context.getRuntimeLifeStyleManager().onStart(execution);
            }
            save(execution);

            LoopResult result = new AgentLoopStepRunner(context).run(execution, control);

            if (result.status() == LoopResult.Status.SUSPENDED) {
                this.context.getConversationManager()
                        .appendSystemMessage(execution, SUSPENDED_CONTEXT_MESSAGE);
                this.context.getRuntimeLifeStyleManager().onSuspend(execution);
            } else if (result.status() == LoopResult.Status.COMPLETED) {
                this.context.getRuntimeLifeStyleManager().onComplete(execution);
                notifyLoopHook(execution, result.writeToolExecuted());
            } else if (result.status() == LoopResult.Status.CANCELLED) {
                this.context.getRuntimeLifeStyleManager().onCancel(execution);
                notifyLoopHook(execution, result.writeToolExecuted());
            } else {
                throw new IllegalStateException("loop returned a non-terminal result: " + result.status());
            }

            return execution;

        }catch (Exception e){
            this.context.getRuntimeLifeStyleManager().onError(execution,e);
            throw new RuntimeException(e);
        }finally {
            if (execution.getMessages() != null) {
                execution.setMessages(List.copyOf(execution.getMessages()));
            }
            try {
                save(execution);
            } finally {
                this.context.getActiveExecutionRegistry().unregister(control);
            }
        }
    }

    private void save(Execution execution) {
        if (context.getExecutionRepository() != null) {
            context.getExecutionRepository().save(execution);
        }
    }


    /**
     * Application hook finalization: the loop hook is told how the run ended (whether a write tool really ran and whether the model closed with plain text) so the application can close its own state. Guarded so a hook failure can never turn a successful execution into a failed one.
     */
    private void notifyLoopHook(Execution execution, boolean writeToolExecuted) {
        try {
            if (this.context.getAgentLoopHook() != null) {
                this.context.getAgentLoopHook().onExecutionFinished(execution,
                        new LoopExecutionOutcome(writeToolExecuted));
            }
        } catch (Exception e) {
            log.warn("【agent-loop】application hook finalisation failed: executionId={}, error={}", execution.getId(), e.getMessage());
        }
    }

}
