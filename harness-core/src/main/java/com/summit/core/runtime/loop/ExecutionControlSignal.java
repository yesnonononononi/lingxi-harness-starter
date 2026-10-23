package com.summit.core.runtime.loop;

import com.summit.core.runtime.loop.suspension.ExecutionInterruptedException;
import lombok.Getter;
import lombok.RequiredArgsConstructor;

/** Cooperative control signal for one currently running execution. */
@RequiredArgsConstructor
@Getter
public final class ExecutionControlSignal {
    private final String executionId;
    private volatile Intention intention = Intention.NONE;

    public enum Intention {
        NONE,
        REQUIRE_SUSPEND,
        REQUIRE_CANCEL
    }

    public boolean isSuspendRequired() {
        return intention == Intention.REQUIRE_SUSPEND;
    }

    public boolean isCancelRequired() {
        return intention == Intention.REQUIRE_CANCEL;
    }

    public void checkInterruption() {
        if (isCancelRequired() || Thread.currentThread().isInterrupted()) {
            throw new ExecutionInterruptedException(ExecutionInterruptedException.Kind.CANCEL,
                    "execution cancellation requested");
        }
        if (isSuspendRequired()) {
            throw new ExecutionInterruptedException(ExecutionInterruptedException.Kind.SUSPEND,
                    "execution suspension requested");
        }
    }

    public synchronized void requireSuspend() {
        if (intention == Intention.NONE) intention = Intention.REQUIRE_SUSPEND;
    }

    public synchronized void requireCancel() {
        intention = Intention.REQUIRE_CANCEL;
    }
}
