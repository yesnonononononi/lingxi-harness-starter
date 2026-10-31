package com.summit.core.utils;

import com.summit.core.exception.NonRetriableModelException;

import java.util.Objects;
import java.util.concurrent.CancellationException;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;
import java.util.function.Predicate;
import java.util.function.Supplier;
import static com.summit.core.exception.CancellationException.create;

public class RetryUtil {
    private static final long ABSOLUTE_MAX_DELAY_MILLIS = 60_000L;

    /**
     * The method body executed by one attempt of {@link #retry(RetryConf)}.
     *
     * <p>A dedicated interface instead of {@link java.util.concurrent.Callable}: {@code execute()}
     * keeps the checked exception ({@code throws Exception}) that a retryable method body needs,
     * and the name states the intent — the task <em>is</em> a method executor, not a value held
     * somewhere. Because every attempt takes a fresh instance from
     * {@link RetryConf#task()}, a task can be written as
     * {@code () -> transactionTemplate.execute(status -> work())} so that each retry runs in its
     * own transaction boundary.</p>
     *
     * @param <T> the result type of the method body
     */
    @FunctionalInterface
    public interface Task<T> {
        /**
         * Runs the method body of this attempt.
         *
         * @return the result of the body
         * @throws Exception any failure of the body; the retry policy decides whether to retry
         */
        T execute() throws Exception;
    }

    /**
     * Retry configuration.
     *
     * <p>{@code task} is a {@link Supplier} of {@link Task} rather than a task instance itself:
     * every attempt — the first one included — asks the supplier for a fresh task instance, so
     * each retry can run in its own transaction boundary or use a brand new command object. A
     * supplier returning {@code null} fails the attempt with a {@link NullPointerException}
     * instead of silently skipping it.</p>
     */
    public record RetryConf<T>(long basicTimeout, int maxRetry, int multiple, long maxDelay,
                               TimeUnit timeUnit, Supplier<Task<T>> task, ControlSignal signal,
                               Consumer<RetryContext> ifRetry, Predicate<Throwable> execRequireRetry) { }

    /** Retry notifications always describe the actual bounded delay in milliseconds. */
    public record RetryContext(int retryCount, long delay, TimeUnit timeUnit) { }

    @FunctionalInterface
    public interface ControlSignal {
        boolean requireCancel(RetryContext retryContext);
    }

    /**
     * Runs {@code config.task()} under the retry policy of {@code config}.
     *
     * <p>{@code task} is a {@code Supplier<Task>}: it is consulted once before every attempt, so
     * each retry receives its own {@link Task} instance and may therefore open an independent
     * transaction boundary or a fresh command object.</p>
     *
     * @param config the retry configuration; its supplier and time unit must not be {@code null}.
     * @param <T>    the result type of the task.
     * @return the first successful result of the task.
     * @throws NullPointerException    if the supplier is {@code null} or produces a {@code null} task.
     * @throws InterruptedException    if the thread is interrupted before or during a backoff wait;
     *                                 the interrupt flag is restored.
     * @throws Exception               the last task failure once the retry budget is exhausted, or
     *                                 any failure the retry predicate rejects.
     */
    public static <T> T retry(RetryConf<T> config) throws Exception {
        Objects.requireNonNull(config, "Retry config cannot be null");
        Objects.requireNonNull(config.task(), "Task supplier cannot be null");
        Objects.requireNonNull(config.timeUnit(), "Time unit cannot be null");
        int retryCount = 0;
        RetryContext context = new RetryContext(0, 0, TimeUnit.MILLISECONDS);
        while (true) {
            checkSignal(config.signal(), context);
            // Every attempt — including the first — takes a fresh task from the supplier and
            // validates it before dispatch, so a retry never reuses an exhausted instance.
            Task<T> task = Objects.requireNonNull(config.task().get(), "Task cannot be null");
            try {
                return task.execute();
            } catch (CancellationException | NonRetriableModelException error) {
                throw error;
            } catch (InterruptedException error) {
                Thread.currentThread().interrupt();
                throw error;
            } catch (Exception error) {
                if (retryCount >= Math.max(config.maxRetry(), 0)
                        || config.execRequireRetry() != null && !config.execRequireRetry().test(error)) {
                    throw error;
                }
                long limit = Math.clamp(config.timeUnit().toMillis(config.maxDelay()), 0, ABSOLUTE_MAX_DELAY_MILLIS);
                long exponential = (long) (config.timeUnit().toMillis(Math.max(config.basicTimeout(), 0))
                        * Math.pow(Math.max(config.multiple(), 1), retryCount));
                long delay = ThreadLocalRandom.current().nextLong(Math.min(exponential, limit) + 1);
                context = new RetryContext(++retryCount, delay, TimeUnit.MILLISECONDS);
                checkSignal(config.signal(), context);
                if (config.ifRetry() != null) config.ifRetry().accept(context);
                awaitRetry(config.signal(), context);
            }
        }
    }

    private static void awaitRetry(ControlSignal signal, RetryContext context) throws InterruptedException {
        long deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(context.delay());
        while (true) {
            checkSignal(signal, context);
            long remaining = deadline - System.nanoTime();
            if (remaining <= 0) return;
            try {
                TimeUnit.NANOSECONDS.sleep(Math.min(remaining, TimeUnit.MILLISECONDS.toNanos(100)));
            } catch (InterruptedException error) {
                Thread.currentThread().interrupt();
                throw error;
            }
        }
    }

    private static void checkSignal(ControlSignal signal, RetryContext context) throws InterruptedException {
        if (Thread.currentThread().isInterrupted()) throw new InterruptedException("retry interrupted");
        if (signal != null && signal.requireCancel(context)) throw create();
    }
}
