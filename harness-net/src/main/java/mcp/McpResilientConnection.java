package mcp;

import io.modelcontextprotocol.client.McpSyncClient;
import io.modelcontextprotocol.spec.McpSchema;
import lombok.extern.slf4j.Slf4j;

import java.time.Duration;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.locks.ReentrantLock;
import java.util.function.Supplier;

/**
 * Owns one MCP client of one server: it hands the current client out, pings it in the background,
 * and rebuilds it after a failure.
 *
 * <h2>Publication rules</h2>
 *
 * <p>The client lives in an {@link AtomicReference} that is only replaced under {@link #swapLock}.
 * Every publication checks {@link #closed} inside that lock, and the replaced handle is closed inside
 * it as well. A rebuild itself — creating the transport and completing initialize — happens
 * <em>outside</em> the lock, because it can take seconds and must not block an unrelated close or
 * ping. Together these two rules make it impossible for a background task to publish a fresh client
 * into a connection that is being closed: either the close wins the lock and the rebuild discards its
 * new handle, or the rebuild wins and the close picks the new handle up and closes it.</p>
 *
 * <h2>Giving up</h2>
 *
 * <p>After {@link McpClientSettings#reconnectAttempts()} consecutive failures the connection is marked
 * unavailable: the client is dropped, {@link #client()} reports it, and the health-check task stops.
 * The tools of a dead server stay published and answer with an error — a background task never
 * removes anything from the caller's tool set, that decision belongs to the owner.</p>
 */
@Slf4j
public final class McpResilientConnection {

    private final Supplier<McpClientHandle> handleFactory;
    private final McpClientSettings settings;
    private final ScheduledExecutorService healthScheduler;
    /** Server this connection belongs to; only used to make the log lines attributable. */
    private final String serverName;
    private final AtomicReference<McpClientHandle> current = new AtomicReference<>();
    private final ReentrantLock swapLock = new ReentrantLock();

    private volatile boolean closed;
    private volatile Duration reconnectFloor;
    private volatile Duration healthCheckInterval;
    private volatile ScheduledFuture<?> healthTask;

    /**
     * @param handleFactory creates a connected and initialized handle; called once per connection
     *                      attempt, so it must produce a brand-new transport each time
     * @param settings      tuning of the timeouts, the backoff and the attempt count
     * @param healthScheduler daemon scheduler shared by every connection; may be null in unit tests,
     *                      in which case no background health check is scheduled
     */
    public McpResilientConnection(Supplier<McpClientHandle> handleFactory, McpClientSettings settings,
                                  ScheduledExecutorService healthScheduler) {
        this(null, handleFactory, settings, healthScheduler);
    }

    /**
     * @param serverName    server this connection belongs to; only used in log lines, so that
     *                      several failing servers are still distinguishable in one log file
     * @param handleFactory creates a connected and initialized handle; called once per connection
     *                      attempt, so it must produce a brand-new transport each time
     * @param settings      tuning of the timeouts, the backoff and the attempt count
     * @param healthScheduler daemon scheduler shared by every connection; may be null in unit tests,
     *                      in which case no background health check is scheduled
     */
    public McpResilientConnection(String serverName, Supplier<McpClientHandle> handleFactory,
                                  McpClientSettings settings, ScheduledExecutorService healthScheduler) {
        this.serverName = serverName;
        this.handleFactory = handleFactory;
        this.settings = settings == null ? McpClientSettings.defaults() : settings;
        this.healthScheduler = healthScheduler;
        this.reconnectFloor = this.settings.defaultReconnectInterval();
        this.healthCheckInterval = this.settings.defaultHealthCheckInterval();
    }

    /** Prefix of every log line; the owner knows its own name, so the connection only echoes it. */
    private String describeSelf() {
        return serverName == null || serverName.isBlank() ? "MCP connection" : "MCP connection '" + serverName + "'";
    }

    /**
     * Creates and publishes the first handle.
     *
     * @throws RuntimeException when the server cannot be reached or cannot be initialized; the
     *                          exception carries the transport's own diagnosis
     */
    public void start() {
        McpClientHandle handle = requireHandle();
        publish(handle);
    }

    /**
     * @return the client to use now
     * @throws IllegalStateException when the connection is closed or has been given up
     */
    public McpSyncClient client() {
        McpClientHandle handle = current.get();
        if (handle == null || closed) {
            throw new IllegalStateException("MCP connection is unavailable");
        }
        return handle.client();
    }

    /** @return the initialize result of the current client, or null when there is none */
    public McpSchema.InitializeResult initializationResult() {
        McpClientHandle handle = current.get();
        return handle == null ? null : handle.client().getCurrentInitializationResult();
    }

    /** @return whether a usable client is currently published */
    public boolean isAvailable() {
        return !closed && current.get() != null;
    }

    /**
     * Starts the periodic ping of this connection.
     *
     * @param interval        interval between two pings; null keeps the settings default
     * @param autoHealthCheck whether to schedule anything at all
     */
    public void scheduleHealthCheck(Duration interval, boolean autoHealthCheck) {
        this.healthCheckInterval = interval == null || interval.isZero() || interval.isNegative()
                ? settings.defaultHealthCheckInterval()
                : interval;
        if (!autoHealthCheck) {
            log.debug("MCP health check disabled by configuration");
            return;
        }
        if (healthScheduler == null || closed) {
            return;
        }
        long period = Math.max(1L, healthCheckInterval.toMillis());
        try {
            healthTask = healthScheduler.scheduleWithFixedDelay(this::runHealthCheck, period, period,
                    TimeUnit.MILLISECONDS);
        } catch (RejectedExecutionException failure) {
            // The scheduler is already shutting down: the connection simply runs unmonitored.
            log.warn("{} health check could not be scheduled ({})", describeSelf(),
                    failure.getClass().getSimpleName());
        }
    }

    /**
     * Sets the floor between two reconnect attempts, i.e. the per-server {@code reconnect-interval}.
     *
     * @param interval configured floor; null keeps the settings default
     */
    public void applyReconnectInterval(Duration interval) {
        this.reconnectFloor = interval == null || interval.isZero() || interval.isNegative()
                ? settings.defaultReconnectInterval()
                : interval;
    }

    /**
     * Pings the client once and rebuilds the connection when the ping fails.
     *
     * <p>Never throws: a health check runs on a shared daemon thread and a failure there must not
     * kill the scheduler. A connection that already gave up is not probed again — its owner decides
     * whether and when to rebuild it.</p>
     */
    public void checkHealth() {
        if (closed || !isAvailable()) {
            return;
        }
        try {
            client().ping();
        } catch (RuntimeException failure) {
            log.warn("{} health check failed ({}: {}); reconnecting", describeSelf(),
                    rootCauseOf(failure).getClass().getSimpleName(), rootCauseOf(failure).getMessage());
            if (!reconnect()) {
                log.warn("{} gave up reconnecting after {} attempts; its tools now report an error",
                        describeSelf(), settings.reconnectAttempts());
            }
        }
    }

    /**
     * Rebuilds the connection with a bounded number of jittered attempts.
     *
     * <p>Only one rebuild proceeds at a time: concurrent callers are serialized on the swap lock at
     * publication time, and an attempt that loses the race simply publishes nothing.</p>
     *
     * @return {@code true} when a new client was published, {@code false} when the connection is
     *         closed or every attempt failed
     */
    public boolean reconnect() {
        if (closed) {
            return false;
        }
        int attempts = settings.reconnectAttempts();
        for (int attempt = 0; attempt < attempts; attempt++) {
            if (attempt > 0 && !awaitBackoff(attempt)) {
                return false;
            }
            if (closed) {
                return false;
            }
            try {
                McpClientHandle handle = requireHandle();
                if (publish(handle)) {
                    log.info("{} re-established on attempt {}", describeSelf(), attempt + 1);
                    return true;
                }
                return false;
            } catch (RuntimeException | Error failure) {
                Throwable root = rootCauseOf(failure);
                log.warn("{} reconnect attempt {} of {} failed ({}: {})", describeSelf(),
                        attempt + 1, attempts, root.getClass().getSimpleName(), root.getMessage());
            }
        }
        markUnavailable();
        return false;
    }

    /**
     * Closes the connection for good: stops the health check, then closes the current handle while
     * holding the swap lock so no rebuild can slip a new client in. Idempotent.
     */
    public void close() {
        swapLock.lock();
        try {
            if (closed) {
                return;
            }
            closed = true;
            cancelHealthTask();
            McpClientHandle handle = current.getAndSet(null);
            if (handle != null) {
                closeHandle(handle);
            }
        } finally {
            swapLock.unlock();
        }
    }

    /** @return whether {@link #close()} has run */
    public boolean isClosed() {
        return closed;
    }

    /** Creates a handle and rejects a factory that produced nothing, which would hide a bug. */
    private McpClientHandle requireHandle() {
        McpClientHandle handle = handleFactory.get();
        if (handle == null) {
            throw new IllegalStateException("MCP handle factory returned no handle");
        }
        return handle;
    }

    /** Publishes a new handle unless the connection is closing, replacing and closing the old one. */
    private boolean publish(McpClientHandle handle) {
        swapLock.lock();
        try {
            if (closed) {
                closeHandle(handle);
                return false;
            }
            McpClientHandle previous = current.getAndSet(handle);
            if (previous != null && previous != handle) {
                closeHandle(previous);
            }
            return true;
        } finally {
            swapLock.unlock();
        }
    }

    /** Drops the current handle after the attempts are exhausted and stops monitoring. */
    private void markUnavailable() {
        swapLock.lock();
        try {
            if (closed) {
                return;
            }
            cancelHealthTask();
            McpClientHandle handle = current.getAndSet(null);
            if (handle != null) {
                closeHandle(handle);
            }
        } finally {
            swapLock.unlock();
        }
    }

    /**
     * Waits before the next attempt, honoring both the jittered backoff and the configured floor.
     *
     * @return {@code false} when the wait was interrupted or the connection closed meanwhile
     */
    private boolean awaitBackoff(int attempt) {
        long backoff = McpBackoff.resolveDelayMillis(settings.backoffBaseMillis(), settings.backoffMultiple(),
                attempt - 1, settings.backoffCapMillis());
        long floor = Math.max(0L, reconnectFloor == null ? 0L : reconnectFloor.toMillis());
        long delay = Math.max(backoff, floor);
        if (delay <= 0L) {
            return true;
        }
        try {
            Thread.sleep(delay);
            return !closed;
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            return false;
        }
    }

    private void runHealthCheck() {
        try {
            checkHealth();
        } catch (RuntimeException failure) {
            // A scheduled task that throws is never rescheduled, so nothing may escape from here.
            Throwable root = rootCauseOf(failure);
            log.warn("{} health check aborted ({}: {})", describeSelf(),
                    root.getClass().getSimpleName(), root.getMessage());
        }
    }

    private void cancelHealthTask() {
        ScheduledFuture<?> task = healthTask;
        healthTask = null;
        if (task != null) {
            task.cancel(false);
        }
    }

    /** Graceful close first, immediate close as the fallback: the documented shutdown contract. */
    private void closeHandle(McpClientHandle handle) {
        try {
            if (!handle.client().closeGracefully()) {
                log.debug("MCP client did not close gracefully; closing immediately");
                handle.close();
            }
        } catch (RuntimeException failure) {
            log.warn("MCP client close failed ({}); closing immediately",
                    failure.getClass().getSimpleName());
            try {
                handle.close();
            } catch (RuntimeException ignored) {
                log.warn("MCP client immediate close failed ({})", ignored.getClass().getSimpleName());
            }
        }
    }

    /** Unwraps the wrapper exceptions the transports add, so the transport-level reason is reached. */
    private static Throwable rootCauseOf(Throwable failure) {
        Throwable current = failure;
        while (current.getCause() != null && current.getCause() != current) {
            current = current.getCause();
        }
        return current;
    }
}
