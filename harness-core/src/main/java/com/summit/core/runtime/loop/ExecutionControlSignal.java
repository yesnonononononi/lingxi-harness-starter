package com.summit.core.runtime.loop;

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

    public void requireSuspend() {
        if (intention == Intention.NONE) intention = Intention.REQUIRE_SUSPEND;
    }

    public void requireCancel() {
        intention = Intention.REQUIRE_CANCEL;
    }
}
