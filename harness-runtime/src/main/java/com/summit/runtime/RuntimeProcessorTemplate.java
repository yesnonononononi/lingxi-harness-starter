package com.summit.runtime;

import com.summit.core.agent.Execution;
import com.summit.core.agent.ExecutionState;
import com.summit.core.conversation.context.RuntimeContext;
import com.summit.core.runtime.ExecutionRuntime;
import com.summit.core.runtime.LifeStyleCommandRegistry;
import com.summit.core.runtime.LifeStyleCommandStore;
import com.summit.runtime.agent.AgentLoopRunner;
import lombok.AllArgsConstructor;
import lombok.extern.slf4j.Slf4j;

import java.io.Serializable;

/**
 * End-to-end orchestration of a single execution: publishes lifecycle events, delegates the agent loop
 * to {@link AgentLoopRunner}, persists messages and token usage, and finalises per final state. The
 * per-execution command store (created by the factory) is released here.
 */
@AllArgsConstructor
@Slf4j
public class RuntimeProcessorTemplate implements ExecutionRuntime {
    private final RuntimeContext context;

    @Override
    public Execution execute(Execution execution) {
        Serializable sessionId = execution.getSessionId();
        this.context.getRuntimeLifeStyleManager().onStart(execution);
        try {
            AgentLoopRunner agentLoop = new AgentLoopRunner(context);

            agentLoop.run(execution, sessionId);


            saveTokenInfoAndMessages(execution);

            if (execution.getExecutionState() == ExecutionState.CANCELLED) {
                this.context.getRuntimeLifeStyleManager().onCancel(execution);
            } else {
                finalizePlan(execution, sessionId,agentLoop);
                this.context.getRuntimeLifeStyleManager().onComplete(execution);
            }

            return execution;

        } catch (Exception e) {
            this.context.getRuntimeLifeStyleManager().onError(execution, e);
            return execution;
        } finally {
            releaseCommandStore(sessionId);
        }
    }

    /**
     * Plan finalization: the plan hook closes the plan when the approved plan was really implemented.
     * Guarded so a hook failure can never turn a successful execution into a failed one.
     */
    private void finalizePlan(Execution execution, Serializable sessionId,AgentLoopRunner agentLoop) {

        try {
            this.context.getPlanLoopHook().onExecutionFinished(execution, sessionId,
                    agentLoop.isExecutedWriteSuccessfully(), agentLoop.isClosedByPlainText());
        } catch (Exception e) {
            log.warn("【agent-loop】plan finalisation failed: executionId={}, error={}", execution.getId(), e.getMessage());
        }
    }

    /**
     * Releases the command store bound to this execution: unregisters it from the per-session registry
     * (identity-protected, so a newer store registered later for the same session is untouched) and
     * clears its command queue.
     */
    private void releaseCommandStore(Serializable sessionId) {
        LifeStyleCommandStore store = context.getLifeStyleCommandStore();
        LifeStyleCommandRegistry registry = context.getLifeStyleCommandRegistry();
        if (store != null && registry != null) {
            registry.unregister(sessionId, store);
            store.destroy();
        }
    }


    private void saveTokenInfoAndMessages(Execution execution) {
        execution.setMessages(this.context.getConversationManager().messages(execution.getSessionId()));
        execution.setTokenUsage(this.context.getConversationManager().tokenUsage(execution.getSessionId()));
    }
}
