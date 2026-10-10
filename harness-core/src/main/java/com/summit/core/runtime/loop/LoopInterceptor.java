package com.summit.core.runtime.loop;

import com.summit.core.agent.Execution;
import com.summit.core.conversation.api.ChatResponseEntity;
import com.summit.core.tool.ToolExecuteResult;

import java.util.List;

/**
 * Synchronous callbacks on the execution thread, driven by {@link LoopInterceptorProcessor} in
 * ascending {@link #order()}. Exceptions are contained by default; override {@link #catchErr()}
 * to propagate them. Runtime budgets are enforced by the loop, independently of this chain.
 */
public interface LoopInterceptor {
    LoopInterceptor NOOP = () -> 0;



    /**
     * intercept consequence. Lower numbers run earlier.
     */
    int order();

    /**
     * Whether the dispatcher logs a callback failure and continues with the remaining interceptors.
     * Run-end notifications remain best-effort even when this returns false.
     */
    default boolean catchErr() {
        return true;
    }

    /**
     * Append external input here, before context budget checks and model request construction.
     */
    default InterceptorResult onBeforeModelInvoke(LoopContext context) {
        return InterceptorResult.NONE;
    }

    default InterceptorResult onAfterModelInvoke(LoopContext context, ChatResponseEntity response) {
        return InterceptorResult.NONE;
    }

    /**
     * Called after tool execution, before Promise/compaction reconciliation and checkpoint persistence.
     */
    default InterceptorResult onAfterToolCall(LoopContext context, List<ToolExecuteResult> results) {
        return InterceptorResult.NONE;
    }

    default InterceptorResult onBeforeToolCall(LoopContext context) {
        return InterceptorResult.NONE;
    }

    /**
     * Called only for a response without tool calls, after its event, messages, token accounting,
     * transcript and execution checkpoint have been recorded, but before natural completion.
     * The execution is still RUNNING. NONE/CONTINUE allow completion; suspension/cancellation
     * retain the committed round. Do not commit the round again from this callback.
     */
    default InterceptorResult onBeforeComplete(LoopContext context) {
        return InterceptorResult.NONE;
    }

    /**
     * Always paired with an entered round, including failure/suspension; failures follow catchErr().
     * A propagated failure becomes suppressed when the round already has a primary failure.
     */
    default InterceptorResult onLoopEnd(LoopContext context) {
        return InterceptorResult.NONE;
    }

    default InterceptorResult onLoopStart(LoopContext context) {
        return InterceptorResult.NONE;
    }

    /**
     * Once on COMPLETED/CANCELLED/FAILED, never on SUSPENDED; exceptions are logged.
     */
    default InterceptorResult onRunEnd(Execution execution) {
        return InterceptorResult.NONE;
    }



}
