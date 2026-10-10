package com.summit.runtime.loop;

import com.summit.core.conversation.api.ChatResponseEntity;
import com.summit.core.runtime.loop.ExecutionControlSignal;
import com.summit.core.runtime.loop.InterceptorResult;
import com.summit.core.runtime.loop.LoopContext;
import com.summit.core.runtime.loop.LoopInterceptor;
import com.summit.core.runtime.loop.LoopResult;
import com.summit.core.tool.ToolExecuteResult;
import org.jspecify.annotations.NonNull;

import java.util.List;

/** Checks cooperative control signals before application callbacks observe each phase. */
public class DefaultLoopInterceptor implements LoopInterceptor {
    @Override
    public boolean catchErr() {
        return false;
    }

    @Override
    public int order() {
        return Integer.MIN_VALUE;
    }

    @Override
    public InterceptorResult onLoopStart(@NonNull LoopContext context) {
        return checkSignal(context.getSignal());
    }

    @Override
    public InterceptorResult onBeforeModelInvoke(@NonNull LoopContext context) {
        return checkSignal(context.getSignal());
    }

    @Override
    public InterceptorResult onAfterModelInvoke(@NonNull LoopContext context, ChatResponseEntity response) {
        return checkSignal(context.getSignal());
    }

    @Override
    public InterceptorResult onBeforeToolCall(@NonNull LoopContext context) {
        return checkSignal(context.getSignal());
    }

    @Override
    public InterceptorResult onAfterToolCall(@NonNull LoopContext context, List<ToolExecuteResult> results) {
        return checkSignal(context.getSignal());
    }

    @Override
    public InterceptorResult onBeforeComplete(@NonNull LoopContext context) {
        return checkSignal(context.getSignal());
    }

    @Override
    public InterceptorResult onLoopEnd(@NonNull LoopContext context) {
        return checkSignal(context.getSignal());
    }

    private InterceptorResult checkSignal(ExecutionControlSignal control) {
        if (control.isCancelRequired()) {
            return InterceptorResult.of(LoopResult.cancelled("execution cancellation requested"));
        }
        if (control.isSuspendRequired()) {
            return InterceptorResult.of(LoopResult.suspended("execution suspension requested"));
        }
        return InterceptorResult.NONE;
    }
}
