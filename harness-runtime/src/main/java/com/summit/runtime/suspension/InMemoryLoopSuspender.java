package com.summit.runtime.suspension;

import com.summit.core.runtime.LoopSuspender;
import com.summit.core.runtime.SuspensionDecision;
import com.summit.core.runtime.SuspensionRequest;

import java.time.Instant;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;

/** Development-friendly in-memory {@link LoopSuspender}. */
public class InMemoryLoopSuspender implements LoopSuspender {

    private final Map<String, Pending> pending = new ConcurrentHashMap<>();

    @Override
    public SuspensionDecision suspend(SuspensionRequest request, Consumer<Suspension> onSuspended) {

        Pending created = new Pending(new Suspension(request, Instant.now()), new CompletableFuture<>());

        Pending existing = pending.putIfAbsent(request.id(), created);

        Pending current = existing == null ? created : existing;

        try {
            onSuspended.accept(current.suspension);
            long timeoutMillis = Math.max(1, request.timeout().toMillis());
            return current.decision.get(timeoutMillis, TimeUnit.MILLISECONDS);

        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return new SuspensionDecision(SuspensionDecision.Status.CANCELLED, Map.of(), "suspension interrupted");
        } catch (java.util.concurrent.TimeoutException e) {
            return new SuspensionDecision(SuspensionDecision.Status.TIMED_OUT, Map.of(), "suspension timed out");
        } catch (java.util.concurrent.ExecutionException e) {
            return new SuspensionDecision(SuspensionDecision.Status.CANCELLED, Map.of(), e.getMessage());
        } finally {
            pending.remove(request.id(), current);
        }
    }

    @Override
    public boolean resolve(String suspensionId, SuspensionDecision decision) {
        Pending value = pending.get(suspensionId);
        return value != null && value.decision.complete(decision);
    }

    @Override
    public boolean cancel(String suspensionId, String reason) {
        return resolve(suspensionId,
                new SuspensionDecision(SuspensionDecision.Status.CANCELLED, Map.of(), reason));
    }

    @Override
    public Optional<Suspension> find(String suspensionId) {
        Pending value = pending.get(suspensionId);
        return value == null ? Optional.empty() : Optional.of(value.suspension);
    }

    private record Pending(Suspension suspension, CompletableFuture<SuspensionDecision> decision) {}
}
