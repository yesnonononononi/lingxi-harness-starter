package com.summit.core.utils;

import com.summit.core.exception.NonRetriableModelException;

import java.util.Objects;
import java.util.concurrent.Callable;
import java.util.concurrent.CancellationException;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;
import java.util.function.Predicate;
import static com.summit.core.exception.CancellationException.create;

public class RetryUtil {
    private static final long ABSOLUTE_MAX_DELAY_MILLIS = 60_000L;

    public record RetryConf<T>(long basicTimeout, int maxRetry, int multiple, long maxDelay,
                               TimeUnit timeUnit, Callable<T> task, ControlSignal signal,
                               Consumer<RetryContext> ifRetry, Predicate<Throwable> execRequireRetry) { }

    /** Retry notifications always describe the actual bounded delay in milliseconds. */
    public record RetryContext(int retryCount, long delay, TimeUnit timeUnit) { }

    @FunctionalInterface
    public interface ControlSignal {
        boolean requireCancel(RetryContext retryContext);
    }

    public static <T> T retry(RetryConf<T> config) throws Exception {
        Objects.requireNonNull(config.task(), "Task cannot be null");
        Objects.requireNonNull(config.timeUnit(), "Time unit cannot be null");
        int retryCount = 0;
        RetryContext context = new RetryContext(0, 0, TimeUnit.MILLISECONDS);
        while (true) {
            checkSignal(config.signal(), context);
            try {
                return config.task().call();
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
