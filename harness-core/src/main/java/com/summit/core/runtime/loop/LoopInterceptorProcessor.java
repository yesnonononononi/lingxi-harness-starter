package com.summit.core.runtime.loop;

import com.summit.core.agent.Execution;
import com.summit.core.conversation.api.ChatResponseEntity;
import com.summit.core.tool.ToolExecuteResult;

import java.util.List;

public interface LoopInterceptorProcessor {
    InterceptorResult NONE = InterceptorResult.NONE;

    default InterceptorResult onBeforeModelInvoke(LoopContext context) {
        return NONE;
    }

    default InterceptorResult onAfterModelInvoke(LoopContext context, ChatResponseEntity response) {
        return NONE;
    }

    /**
     * Called after a successful tool batch is committed or its compaction is reconciled.
     */
    default InterceptorResult onAfterToolCall(LoopContext context, List<ToolExecuteResult> results) {
        return NONE;
    }

    default InterceptorResult onBeforeToolCall(LoopContext context) {
        return NONE;
    }

    /**
     * Always paired with an entered round, including failure/suspension.
     * A propagated failure becomes suppressed when the round already has a primary failure.
     */
    default InterceptorResult onLoopEnd(LoopContext context) {
        return NONE;
    }

    default InterceptorResult onLoopStart(LoopContext context) {
        return NONE;
    }

    /**
     * Once on COMPLETED/CANCELLED/FAILED, never on SUSPENDED; exceptions are logged.
     */
    default InterceptorResult onRunEnd(Execution execution) {
        return NONE;
    }
}
