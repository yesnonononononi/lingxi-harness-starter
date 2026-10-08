package com.summit.runtime.loop;

import com.summit.core.agent.Execution;
import com.summit.core.runtime.loop.CheckPointResult;
import com.summit.core.runtime.loop.ExecutionControlSignal;
import com.summit.core.runtime.loop.LoopResult;
import com.summit.core.runtime.loop.RuntimeBoundaryChecker;
import org.jspecify.annotations.NonNull;

import java.util.Objects;

/** Applies runtime limits outside the interceptor chain, with control intent taking priority. */
final class LoopBoundaryGuard {
    private final RuntimeBoundaryChecker checker;

    LoopBoundaryGuard(RuntimeBoundaryChecker checker) {
        this.checker = Objects.requireNonNull(checker, "runtimeBoundaryChecker");
    }

    @NonNull LoopResult beforeModel(Execution execution, ExecutionControlSignal control) {
        LoopResult signal = resolveControlResult(control);
        if (!signal.shouldContinue()) return signal;

        LoopResult result = resolveBoundaryResult(checker.before(execution), control);
        // A callback or boundary that stops this attempt must not consume the model budget.
        if (result.shouldContinue()) execution.incrementModelAttempts();
        return result;
    }

    @NonNull LoopResult afterTools(Execution execution, ExecutionControlSignal control) {
        LoopResult signal = resolveControlResult(control);
        if (!signal.shouldContinue()) return signal;
        return resolveBoundaryResult(checker.after(execution), control);
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
