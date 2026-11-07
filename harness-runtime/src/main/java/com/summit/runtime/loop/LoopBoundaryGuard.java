package com.summit.runtime.loop;

import com.summit.core.agent.Execution;
import com.summit.core.conversation.message.TokenUsageEntity;
import com.summit.core.runtime.loop.*;
import lombok.AllArgsConstructor;
import org.jspecify.annotations.NonNull;

/** Applies runtime limits outside the interceptor chain, with control intent taking priority. */
@AllArgsConstructor
final class LoopBoundaryGuard {
    private final RuntimeBoundaryChecker checker;
    private final ContextUsageReporter usage;



    @NonNull LoopResult beforeModel(TokenUsageEntity tokenUsageBaseLine, Execution execution, ExecutionControlSignal control) {
        LoopResult signal = resolveControlResult(control);
        if (!signal.shouldContinue()) return signal;

        tokenUsageBaseLine.setTotalTokens(usage.baseline(execution).getTotalTokens());

        return resolveBoundaryResult(checker.before(tokenUsageBaseLine,execution), control);
    }

    @NonNull LoopResult afterTools(TokenUsageEntity tokenUsageBaseLine, LoopMessages incrementMessages, Execution execution, ExecutionControlSignal control) {
        LoopResult signal = resolveControlResult(control);
        if (!signal.shouldContinue()) return signal;

        tokenUsageBaseLine.setTotalTokens(usage.baseline(execution).getTotalTokens());

        return resolveBoundaryResult(checker.after(tokenUsageBaseLine, execution), control);
    }

    private @NonNull LoopResult resolveBoundaryResult(CheckPointResult boundary, ExecutionControlSignal control) {
        // Compaction can block, so a control request arriving during it still wins over its result.
        LoopResult signal = resolveControlResult(control);
        if (!signal.shouldContinue()) return signal;
        return boundary.isCancelled() ? LoopResult.cancelled(boundary.reason()) : LoopResult.continueLoop();
    }

    private @NonNull LoopResult resolveControlResult(ExecutionControlSignal control) {
        if (control.isCancelRequired()) {
            return LoopResult.cancelled("execution cancellation requested");
        }
        if (control.isSuspendRequired()) {
            return LoopResult.suspended("execution suspension requested");
        }
        return LoopResult.continueLoop();
    }
}
