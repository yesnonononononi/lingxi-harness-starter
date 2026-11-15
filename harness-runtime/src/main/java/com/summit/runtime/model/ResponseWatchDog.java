package com.summit.runtime.model;


import java.time.Duration;
import java.util.Objects;
import java.util.concurrent.*;

public class ResponseWatchDog {
    private static final Duration DEFAULT_CHECK_INTERVAL = Duration.ofSeconds(5);
    private static final ScheduledExecutorService TIMEOUT_SCHEDULER =
            Executors.newSingleThreadScheduledExecutor(r -> {
                Thread t = new Thread(r, "stream-idle-timeout");
                t.setDaemon(true);
                return t;
            });
    private volatile ScheduledFuture<?> watchdogTask;
    private final Runnable heartbeat;
    private final long checkInterval;

    public ResponseWatchDog(Runnable heartbeat) {
        this(heartbeat, DEFAULT_CHECK_INTERVAL);
    }

    /**
     * Creates a watchdog with a configurable polling interval.
     *
     * @param heartbeat     callback invoked periodically while the watchdog is active
     * @param checkInterval polling interval; defaults to 5 seconds when {@code null}
     */
    public ResponseWatchDog(Runnable heartbeat, Duration checkInterval) {
        this.heartbeat = Objects.requireNonNull(heartbeat, "heartbeat must not be null");
        Duration effectiveInterval = checkInterval != null ? checkInterval : DEFAULT_CHECK_INTERVAL;
        if (effectiveInterval.isZero() || effectiveInterval.isNegative()) {
            throw new IllegalArgumentException("checkInterval must be positive");
        }
        this.checkInterval = effectiveInterval.toNanos();
    }


    public synchronized void start() {
        if(this.watchdogTask != null) return;
        watchdogTask = TIMEOUT_SCHEDULER.scheduleWithFixedDelay(heartbeat, checkInterval, checkInterval, TimeUnit.NANOSECONDS);
    }



    public synchronized void stop() {
        if (this.watchdogTask == null) return;
        this.watchdogTask.cancel(false);
        this.watchdogTask = null;
    }

    public boolean isRunning() {
        return this.watchdogTask != null;
    }


}
