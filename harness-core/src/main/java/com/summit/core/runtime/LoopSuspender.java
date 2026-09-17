package com.summit.core.runtime;

import java.time.Instant;
import java.util.Optional;
import java.util.function.Consumer;

/**
 * Application-neutral suspension point for an agent loop.
 *
 * <p>{@link #suspend(SuspensionRequest)} is invoked on the loop thread and returns only after an
 * external actor resolves, cancels or times out the suspension. Applications use the same SPI for
 * approvals, explicit choices, plans and any other human-in-the-loop workflow.</p>
 *
 * <p>The default runtime implementation is in-memory. Production applications can replace this
 * bean with a durable implementation without changing the agent loop or tool executor.</p>
 */
public interface LoopSuspender {

    default SuspensionDecision suspend(SuspensionRequest request) {
        return suspend(request, ignored -> {});
    }

    /** Registers first, invokes the callback, then waits; this avoids resolve-before-register races. */
    SuspensionDecision suspend(SuspensionRequest request, Consumer<Suspension> onSuspended);

    boolean resolve(String suspensionId, SuspensionDecision decision);

    boolean cancel(String suspensionId, String reason);

    Optional<Suspension> find(String suspensionId);

    record Suspension(SuspensionRequest request, Instant createdAt) {
    }
}
