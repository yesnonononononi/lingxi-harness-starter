package mcp;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** The reconnect timing must be bounded, monotonic in its bound, and jittered. */
class McpBackoffTest {

    private static final int SAMPLES = 200;

    @Test
    void firstAttemptStaysWithinTheBase() {
        for (long delay : samples(2_000L, 2, 0, 60_000L)) {
            assertTrue(delay >= 0L && delay <= 2_000L, "first delay out of range: " + delay);
        }
    }

    @Test
    void progressionGrowsByTheMultiple() {
        for (long delay : samples(2_000L, 2, 1, 60_000L)) {
            assertTrue(delay >= 0L && delay <= 4_000L, "second delay out of range: " + delay);
        }
        for (long delay : samples(2_000L, 3, 2, 60_000L)) {
            assertTrue(delay >= 0L && delay <= 18_000L, "third delay out of range: " + delay);
        }
    }

    @Test
    void capWinsOverTheProgression() {
        for (long delay : samples(2_000L, 2, 40, 60_000L)) {
            assertTrue(delay >= 0L && delay <= 60_000L, "capped delay out of range: " + delay);
        }
        // A cap below the base truncates the first step as well.
        for (long delay : samples(2_000L, 2, 0, 500L)) {
            assertTrue(delay >= 0L && delay <= 500L, "truncated delay out of range: " + delay);
        }
    }

    /** A constant progression is legitimate; the interesting part is that it is still jittered. */
    @Test
    void jitterSpreadsTheDelayAcrossTheWholeWindow() {
        List<Long> delays = samples(2_000L, 1, 3, 60_000L);
        long max = delays.stream().mapToLong(Long::longValue).max().orElseThrow();
        long min = delays.stream().mapToLong(Long::longValue).min().orElseThrow();
        assertTrue(max > 1_500L, "jitter must reach the upper part of the window, got " + max);
        assertTrue(min < 500L, "jitter must reach the lower part of the window, got " + min);
    }

    @Test
    void nonPositiveCapYieldsNoWait() {
        assertEquals(0L, McpBackoff.resolveDelayMillis(2_000L, 2, 3, 0L));
        assertEquals(0L, McpBackoff.resolveDelayMillis(2_000L, 2, 3, -1L));
    }

    @Test
    void unusableInputsAreNormalized() {
        // Negative base, multiple and attempt all count as their neutral value, never as an error.
        assertEquals(0L, McpBackoff.resolveDelayMillis(-1L, 2, 3, 60_000L));
        for (long delay : samples(1_000L, 0, 2, 60_000L)) {
            assertTrue(delay >= 0L && delay <= 1_000L, "multiple below one must not grow: " + delay);
        }
        for (long delay : samples(1_000L, 2, -5, 60_000L)) {
            assertTrue(delay >= 0L && delay <= 1_000L, "negative attempt must behave like zero: " + delay);
        }
    }

    private static List<Long> samples(long base, int multiple, int attempt, long cap) {
        List<Long> delays = new ArrayList<>(SAMPLES);
        for (int i = 0; i < SAMPLES; i++) {
            delays.add(McpBackoff.resolveDelayMillis(base, multiple, attempt, cap));
        }
        return delays;
    }
}
