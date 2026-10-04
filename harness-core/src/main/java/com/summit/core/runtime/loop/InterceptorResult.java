package com.summit.core.runtime.loop;

public record InterceptorResult(LoopResult loopResult) {
    public static InterceptorResult NONE = new InterceptorResult(null);

    public static InterceptorResult of(LoopResult loopResult) {
        return new InterceptorResult(loopResult);
    }

    public boolean shouldContinue() {
        return loopResult == null || loopResult.shouldContinue();
    }
}
