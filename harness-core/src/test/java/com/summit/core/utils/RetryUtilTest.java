package com.summit.core.utils;

import com.summit.core.exception.NonRetriableModelException;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CancellationException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Supplier;

import static org.junit.jupiter.api.Assertions.*;

class RetryUtilTest {

    /**
     * Sample count of the randomized backoff bound probe: with this many independent samples a cap
     * that was wrongly widened to 120s survives with a probability of only about 2^-128.
     */
    private static final int DELAY_SAMPLES = 128;

    /**
     * Wraps one task into an always-identical supplier, keeping the retry configurations of this
     * class readable despite the nested generic signature.
     */
    private static <T> Supplier<RetryUtil.Task<T>> task(RetryUtil.Task<T> body) {
        return () -> body;
    }

    @Test
    void notificationOccursBeforeTheFirstBackoffControlCheck() {
        AtomicInteger checks = new AtomicInteger();
        List<RetryUtil.RetryContext> notices = new ArrayList<>();
        assertThrows(NonRetriableModelException.class, () -> RetryUtil.retry(new RetryUtil.RetryConf<>(
                Long.MAX_VALUE, 1, 2, Long.MAX_VALUE, TimeUnit.DAYS,
                task(() -> { throw new IOException("temporary"); }), context -> {
                    if (context.retryCount() == 0 || checks.incrementAndGet() == 1) return false;
                    assertEquals(1, notices.size());
                    return true;
                }, notices::add, error -> true)));
    }

    @Test
    void retryCountMeansAdditionalAttemptsAndOptionalNotificationMayBeAbsent() throws Exception {
        AtomicInteger calls = new AtomicInteger();
        String result = RetryUtil.retry(new RetryUtil.RetryConf<>(0, 2, 2, 0, TimeUnit.MILLISECONDS, task(() -> {
            if (calls.incrementAndGet() < 3) throw new IOException("temporary");
            return "success";
        }), null, null, error -> error instanceof IOException));
        assertEquals("success", result);
        assertEquals(3, calls.get());
    }

    /** The budget counts additional attempts: once exhausted, the original failure is rethrown. */
    @Test
    void exhaustedBudgetRethrowsTheOriginalFailure() {
        IOException failure = new IOException("still broken");
        AtomicInteger calls = new AtomicInteger();
        IOException thrown = assertThrows(IOException.class, () -> RetryUtil.retry(new RetryUtil.RetryConf<>(
                0, 1, 2, 0, TimeUnit.MILLISECONDS,
                task(() -> { calls.incrementAndGet(); throw failure; }), null, null, error -> true)));

        assertSame(failure, thrown);
        assertEquals(2, calls.get());
    }

    @Test
    void cancellationAndNonRetriableFailuresEscapeEvenWithoutAPredicate() {
        for (RuntimeException failure : List.of(new CancellationException("cancelled"),
                new NonRetriableModelException("permanent", null))) {
            AtomicInteger calls = new AtomicInteger();
            assertSame(failure, assertThrows(RuntimeException.class, () -> RetryUtil.retry(new RetryUtil.RetryConf<>(
                    0, 3, 2, 0, TimeUnit.MILLISECONDS, task(() -> { calls.incrementAndGet(); throw failure; }),
                    null, null, null))));
            assertEquals(1, calls.get());
        }
    }

    /**
     * The delay is random inside the capped range, so a single sample can only detect a widened cap
     * about half of the time. Every sample aborts its wait through the cancel signal, so sampling
     * {@value #DELAY_SAMPLES} times stays cheap while making a regressed cap virtually certain to
     * be caught: with the cap wrongly widened to 120s the chance of all samples staying below 60s
     * is about 2^-{@value #DELAY_SAMPLES}.
     */
    @Test
    void noticePrecedesWaitingAndCarriesBoundedMilliseconds() {
        for (int sample = 0; sample < DELAY_SAMPLES; sample++) {
            AtomicInteger calls = new AtomicInteger();
            List<RetryUtil.RetryContext> notices = new ArrayList<>();
            assertThrows(NonRetriableModelException.class, () -> RetryUtil.retry(new RetryUtil.RetryConf<>(
                    Long.MAX_VALUE, 1, Integer.MAX_VALUE, Long.MAX_VALUE, TimeUnit.DAYS,
                    task(() -> { calls.incrementAndGet(); throw new IOException("temporary"); }),
                    context -> !notices.isEmpty(), notices::add, error -> true)));
            assertEquals(1, calls.get());
            assertEquals(1, notices.size());
            assertEquals(1, notices.getFirst().retryCount());
            assertEquals(TimeUnit.MILLISECONDS, notices.getFirst().timeUnit());
            long delay = notices.getFirst().delay();
            assertTrue(delay >= 0 && delay <= 60_000, () -> "delay must stay inside the 60s cap, was " + delay);
        }
    }

    @Test
    void taskInterruptionPreservesTheThreadFlagAndDoesNotRetry() {
        AtomicInteger calls = new AtomicInteger();
        try {
            assertThrows(InterruptedException.class, () -> RetryUtil.retry(new RetryUtil.RetryConf<>(
                    0, 3, 2, 0, TimeUnit.MILLISECONDS,
                    task(() -> { calls.incrementAndGet(); throw new InterruptedException("interrupted"); }),
                    null, null, null)));
            assertTrue(Thread.currentThread().isInterrupted());
            assertEquals(1, calls.get());
        } finally {
            Thread.interrupted();
        }
    }

    @Test
    void preexistingInterruptionDoesNotDispatchTheTask() {
        AtomicInteger calls = new AtomicInteger();
        try {
            Thread.currentThread().interrupt();
            assertThrows(InterruptedException.class, () -> RetryUtil.retry(new RetryUtil.RetryConf<>(
                    0, 3, 2, 0, TimeUnit.MILLISECONDS, task(calls::incrementAndGet), null, null, null)));
            assertEquals(0, calls.get());
            assertTrue(Thread.currentThread().isInterrupted());
        } finally {
            Thread.interrupted();
        }
    }

    /** Every attempt — the first one included — must take a brand new task from the supplier. */
    @Test
    void taskSupplierConsultedOncePerAttempt() throws Exception {
        AtomicInteger supplied = new AtomicInteger();
        List<Integer> created = new ArrayList<>();
        List<Integer> used = new ArrayList<>();
        Supplier<RetryUtil.Task<String>> supplier = () -> {
            int instanceId = supplied.incrementAndGet();
            created.add(instanceId);
            return () -> {
                used.add(instanceId);
                if (instanceId < 3) throw new IOException("temporary");
                return "success";
            };
        };

        String result = RetryUtil.retry(new RetryUtil.RetryConf<>(0, 2, 2, 0, TimeUnit.MILLISECONDS,
                supplier, null, null, error -> error instanceof IOException));

        assertEquals("success", result);
        assertEquals(3, supplied.get());
        assertEquals(List.of(1, 2, 3), created);
        assertEquals(created, used);
    }

    /**
     * A fresh task instance per attempt is what lets each retry open its own transaction boundary:
     * the simulated boundary counter must advance exactly once per attempt.
     */
    @Test
    void freshTaskInstancePerAttemptEnablesNewTransactionBoundary() throws Exception {
        AtomicInteger transactionSequence = new AtomicInteger();
        AtomicInteger attempts = new AtomicInteger();
        Supplier<RetryUtil.Task<String>> supplier = () -> {
            // Each task captures the boundary opened when it was created, exactly like a lambda
            // closing over its own transaction template call.
            int boundary = transactionSequence.incrementAndGet();
            return () -> {
                if (attempts.incrementAndGet() < 3) throw new IOException("rollback " + boundary);
                return "committed@" + boundary;
            };
        };

        String result = RetryUtil.retry(new RetryUtil.RetryConf<>(0, 2, 2, 0, TimeUnit.MILLISECONDS,
                supplier, null, null, error -> error instanceof IOException));

        assertEquals(3, transactionSequence.get());
        assertEquals(3, attempts.get());
        assertEquals("committed@3", result);
    }

    /** A null supplier and a supplier producing a null task are rejected with distinct messages. */
    @Test
    void nullSupplierAndNullTaskAreRejectedWithDistinctMessages() {
        NullPointerException nullSupplier = assertThrows(NullPointerException.class,
                () -> RetryUtil.retry(new RetryUtil.RetryConf<String>(0, 1, 2, 0, TimeUnit.MILLISECONDS,
                        null, null, null, null)));
        assertEquals("Task supplier cannot be null", nullSupplier.getMessage());

        NullPointerException nullTask = assertThrows(NullPointerException.class,
                () -> RetryUtil.retry(new RetryUtil.RetryConf<String>(0, 1, 2, 0, TimeUnit.MILLISECONDS,
                        () -> null, null, null, null)));
        assertEquals("Task cannot be null", nullTask.getMessage());
    }
}
