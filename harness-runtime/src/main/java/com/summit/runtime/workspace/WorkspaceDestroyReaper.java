package com.summit.runtime.workspace;

import com.summit.core.workspace.WorkspaceManager;
import com.summit.core.workspace.WorkspaceRef;
import lombok.extern.slf4j.Slf4j;

import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/** Retries explicitly requested destruction without expiring workspaces that may still be active. */
@Slf4j
public final class WorkspaceDestroyReaper implements WorkspaceDestroyer, AutoCloseable {
    private final WorkspaceManager manager;
    private final Duration maxBackoff;
    private final int maxAttempts;
    private final Map<WorkspaceRef, PendingDestroy> pending = new ConcurrentHashMap<>();
    private final ScheduledExecutorService executor;

    public WorkspaceDestroyReaper(WorkspaceManager manager, Duration interval,
                                  Duration maxBackoff, int maxAttempts) {
        this.manager = Objects.requireNonNull(manager, "workspace manager");
        requirePositive(interval, "cleanup interval");
        requirePositive(maxBackoff, "cleanup max backoff");
        if (maxAttempts < 1) {
            throw new IllegalArgumentException("cleanup max attempts must be positive");
        }
        this.maxBackoff = maxBackoff;
        this.maxAttempts = maxAttempts;
        this.executor = Executors.newSingleThreadScheduledExecutor(task -> {
            Thread thread = new Thread(task, "workspace-destroy-reaper");
            thread.setDaemon(true);
            return thread;
        });
        executor.scheduleWithFixedDelay(this::reapSafely, interval.toMillis(),
                interval.toMillis(), TimeUnit.MILLISECONDS);
    }

    @Override
    public void destroyOrSchedule(WorkspaceRef ref) {
        Objects.requireNonNull(ref, "workspace ref");
        try {
            manager.destroy(ref);
            pending.remove(ref);
        } catch (RuntimeException failure) {
            pending.compute(ref, (ignored, current) -> nextAttempt(ref, current, failure));
        }
    }

    /** Runs one retry pass immediately. */
    public void reapNow() {
        Instant now = Instant.now();
        pending.forEach((ref, item) -> {
            if (item.attempts() < maxAttempts && !item.nextAttemptAt().isAfter(now)) {
                destroyOrSchedule(ref);
            }
        });
    }

    public int pendingCount() {
        return pending.size();
    }

    private PendingDestroy nextAttempt(WorkspaceRef ref, PendingDestroy current,
                                       RuntimeException failure) {
        int attempts = current == null ? 1 : current.attempts() + 1;
        if (attempts >= maxAttempts) {
            log.error("workspace destroy exhausted retries: workspaceId={}, attempts={}",
                    ref.id(), attempts, failure);
            return new PendingDestroy(attempts, Instant.MAX);
        }
        long multiplier = 1L << Math.min(attempts - 1, 20);
        Duration delay = Duration.ofMillis(Math.min(maxBackoff.toMillis(), multiplier * 1000));
        log.warn("workspace destroy failed; retry scheduled: workspaceId={}, attempt={}, delay={}",
                ref.id(), attempts, delay, failure);
        return new PendingDestroy(attempts, Instant.now().plus(delay));
    }

    private void reapSafely() {
        try {
            reapNow();
        } catch (RuntimeException failure) {
            log.error("workspace destroy reaper pass failed", failure);
        }
    }

    private static void requirePositive(Duration value, String name) {
        if (value == null || value.isZero() || value.isNegative()) {
            throw new IllegalArgumentException(name + " must be positive");
        }
    }

    @Override
    public void close() {
        executor.shutdownNow();
    }

    private record PendingDestroy(int attempts, Instant nextAttemptAt) {
    }
}
