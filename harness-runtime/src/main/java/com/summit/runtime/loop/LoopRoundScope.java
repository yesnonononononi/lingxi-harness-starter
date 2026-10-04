package com.summit.runtime.loop;

import com.summit.core.runtime.loop.LoopContext;
import com.summit.core.runtime.loop.LoopInterceptorProcessor;

/** Uses resource cleanup to preserve a round failure when its end callback also fails. */
final class LoopRoundScope implements AutoCloseable {
    private final LoopInterceptorProcessor interceptor;
    private final LoopContext context;

    LoopRoundScope(LoopInterceptorProcessor interceptor, LoopContext context) {
        this.interceptor = interceptor;
        this.context = context;
    }

    @Override
    public void close() {
        interceptor.onLoopEnd(context);
    }
}
