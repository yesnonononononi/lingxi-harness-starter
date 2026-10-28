package com.summit.core.runtime.loop;

import java.util.Objects;

/** Separates interceptor-chain delegation from the loop decision made by a callback. */
public record InterceptorResult(LoopResult loopResult, boolean next) {
    public static final InterceptorResult NONE = new InterceptorResult(LoopResult.continueLoop(), true);

    public InterceptorResult {
        loopResult = Objects.requireNonNullElse(loopResult, LoopResult.continueLoop());
        if (next && !loopResult.shouldContinue()) {
            throw new IllegalArgumentException(
                    "Cannot delegate to the next interceptor with loop status " + loopResult.status());
        }
    }

    /** Set next to false for an explicit decision; NONE delegates without changing the phase outcome. */
    public static InterceptorResult of(LoopResult loopResult, boolean next) {
        return new InterceptorResult(loopResult, next);
    }
}
