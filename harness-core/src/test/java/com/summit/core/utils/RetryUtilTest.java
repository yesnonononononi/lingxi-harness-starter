package com.summit.core.utils;

import com.summit.core.exception.NonRetriableModelException;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CancellationException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

class RetryUtilTest {
    @Test
    void notificationOccursBeforeTheFirstBackoffControlCheck() {
        AtomicInteger checks = new AtomicInteger();
        List<RetryUtil.RetryContext> notices = new ArrayList<>();
        assertThrows(NonRetriableModelException.class, () -> RetryUtil.retry(new RetryUtil.RetryConf<>(
                Long.MAX_VALUE, 1, 2, Long.MAX_VALUE, TimeUnit.DAYS,
                () -> { throw new IOException("temporary"); }, context -> {
                    if (context.retryCount() == 0 || checks.incrementAndGet() == 1) return false;
                    assertEquals(1, notices.size());
                    return true;
                }, notices::add, error -> true)));
    }
    @Test
    void retryCountMeansAdditionalAttemptsAndOptionalNotificationMayBeAbsent() throws Exception {
        AtomicInteger calls = new AtomicInteger();
        String result = RetryUtil.retry(new RetryUtil.RetryConf<>(0, 2, 2, 0, TimeUnit.MILLISECONDS, () -> {
            if (calls.incrementAndGet() < 3) throw new IOException("temporary");
            return "success";
        }, null, null, error -> error instanceof IOException));
        assertEquals("success", result);
        assertEquals(3, calls.get());
    }

    @Test
    void cancellationAndNonRetriableFailuresEscapeEvenWithoutAPredicate() {
        for (RuntimeException failure : List.of(new CancellationException("cancelled"),
                new NonRetriableModelException("permanent", null))) {
            AtomicInteger calls = new AtomicInteger();
            assertSame(failure, assertThrows(RuntimeException.class, () -> RetryUtil.retry(new RetryUtil.RetryConf<>(
                    0, 3, 2, 0, TimeUnit.MILLISECONDS, () -> { calls.incrementAndGet(); throw failure; },
                    null, null, null))));
            assertEquals(1, calls.get());
        }
    }

    @Test
    void noticePrecedesWaitingAndCarriesBoundedMilliseconds() {
        AtomicInteger calls = new AtomicInteger();
        List<RetryUtil.RetryContext> notices = new ArrayList<>();
        assertThrows(NonRetriableModelException.class, () -> RetryUtil.retry(new RetryUtil.RetryConf<>(
                Long.MAX_VALUE, 1, Integer.MAX_VALUE, Long.MAX_VALUE, TimeUnit.DAYS,
                () -> { calls.incrementAndGet(); throw new IOException("temporary"); },
                context -> !notices.isEmpty(), notices::add, error -> true)));
        assertEquals(1, calls.get());
        assertEquals(1, notices.size());
        assertEquals(1, notices.getFirst().retryCount());
        assertEquals(TimeUnit.MILLISECONDS, notices.getFirst().timeUnit());
        assertTrue(notices.getFirst().delay() >= 0 && notices.getFirst().delay() <= 60_000);
    }

    @Test
    void taskInterruptionPreservesTheThreadFlagAndDoesNotRetry() {
        AtomicInteger calls = new AtomicInteger();
        try {
            assertThrows(InterruptedException.class, () -> RetryUtil.retry(new RetryUtil.RetryConf<>(
                    0, 3, 2, 0, TimeUnit.MILLISECONDS,
                    () -> { calls.incrementAndGet(); throw new InterruptedException("interrupted"); },
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
                    0, 3, 2, 0, TimeUnit.MILLISECONDS, calls::incrementAndGet, null, null, null)));
            assertEquals(0, calls.get());
            assertTrue(Thread.currentThread().isInterrupted());
        } finally {
            Thread.interrupted();
        }
    }
}
