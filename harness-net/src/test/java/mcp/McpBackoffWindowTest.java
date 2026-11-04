package mcp;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Pins the exact upper end of the jitter window of {@link McpBackoff}.
 *
 * <p>{@code McpBackoffTest} asserts the delay stays <em>inside</em> a range, which cannot tell a
 * correct progression from one that never grows (both stay under the cap). The jitter is the only
 * window into the saturated value, so each case here samples the window until its top is observed and
 * then compares that top with the value the progression must have produced. The sample count is
 * chosen so that missing the top of the window is astronomically unlikely (for an {@code n} ms window
 * and 2048 samples the chance of never drawing {@code n} is {@code (n/(n+1))^2048}, i.e. below
 * {@code 1e-90} even for the widest window used here).</p>
 *
 * <h2>Equivalent mutants (declared, not asserted around)</h2>
 *
 * <p>PIT reports three conditional-boundary mutants of this class as survived (a fourth survivor,
 * the protocol-version length guard, lives in {@code McpServerValidator} and is documented in
 * {@code McpServerValidatorBoundaryTest}). Each one is provably unobservable; no assertion can
 * distinguish it, so they are recorded here instead of being papered over:</p>
 * <ol>
 *   <li>{@code capMillis <= 0} mutated to {@code < 0} (the early return): with a cap of exactly zero
 *       the body would clamp the base to {@code [0, 0]} — zero — the loop condition
 *       {@code 0 < 0} is false immediately, and the final {@code capped <= 0} guard returns zero as
 *       well. Same result, same number of random draws (none).</li>
 *   <li>{@code capped < capMillis} mutated to {@code <= capMillis} (the loop condition): entering one
 *       extra iteration with {@code capped == capMillis} re-assigns the cap to itself, because
 *       {@code capped > capMillis / factor} holds for every {@code factor >= 2} and the
 *       {@code factor == 1} branch multiplies by one. The value — and therefore the window — is
 *       unchanged.</li>
 *   <li>{@code capped <= 0} mutated to {@code < 0} (the final guard): with {@code capped == 0} the
 *       alternative branch draws {@code nextLong(0 + 1)}, which is always zero. Both spellings return
 *       zero.</li>
 * </ol>
 *
 * <p>The remaining boundary mutant ({@code capped > capMillis / factor} to {@code >=}) is <em>not</em>
 * equivalent — it promotes a {@code capped} that equals the truncated quotient straight to the cap —
 * and is killed by {@link #capDividedByFactorKeepsTheMultiplication()}.</p>
 */
class McpBackoffWindowTest {

    /** Enough draws that the top of a two-digit window is observed with probability ~1. */
    private static final int SAMPLES = 2_048;

    /** Kills "the progression never grows" and "the growth is a division". */
    @Test
    void windowUpperBoundFollowsTheMultiplication() {
        // 1 * 2 * 2 * 2 = 8: three loop iterations must actually run.
        assertEquals(8L, observedMax(1L, 2, 3, 8L),
                "attempt 3 with base 1 and multiple 2 must reach the 8ms window");
        // A division instead of a multiplication collapses the window to zero, which the sampled
        // minimum would then pin at zero forever; a live jitter must also draw below the top.
        assertTrue(observedMin(1L, 2, 3, 8L) < 8L, "the jitter must still spread below the top");
    }

    /**
     * The {@code capped > capMillis / factor} guard is not merely a safety net: when the base equals
     * the truncated quotient, a boundary mutation would jump to the cap instead of doubling.
     */
    @Test
    void capDividedByFactorKeepsTheMultiplication() {
        // 11 / 2 == 5, so a base of 5 sits exactly on the boundary: 5 * 2 == 10 stays below the cap.
        assertEquals(10L, observedMax(5L, 2, 1, 11L),
                "capped == cap / factor must still be multiplied, not promoted to the cap");
    }

    /** Once the growth reaches the cap the window saturates there and never exceeds it. */
    @Test
    void capSaturatesTheProgression() {
        List<Long> delays = samples(5L, 2, 5, 20L);
        long max = delays.stream().mapToLong(Long::longValue).max().orElseThrow();
        long min = delays.stream().mapToLong(Long::longValue).min().orElseThrow();

        assertEquals(20L, max, "the window must saturate at the cap");
        assertTrue(min < 20L, "the jitter must still reach the lower part of the window");
        assertTrue(delays.stream().allMatch(delay -> delay >= 0L && delay <= 20L),
                "no delay may leave [0, cap]");
    }

    /**
     * A non-positive cap means "no wait" — including the zero case the equivalent boundary mutant
     * moves into the body. Every base is covered, so the guard is exercised from both sides.
     */
    @Test
    void nonPositiveCapYieldsNoWaitForEveryBase() {
        for (long base : new long[] {-5L, 0L, 7L, 60_000L}) {
            assertEquals(0L, McpBackoff.resolveDelayMillis(base, 2, 3, 0L),
                    "cap 0 must not wait for base " + base);
            assertEquals(0L, McpBackoff.resolveDelayMillis(base, 2, 3, -1L),
                    "negative cap must not wait for base " + base);
        }
    }

    /** A degraded progression (base 0 or negative) stays at zero instead of throwing. */
    @Test
    void zeroWindowDrawsZero() {
        assertEquals(0L, observedMax(0L, 3, 5, 1_000L), "a zero base never grows");
        assertEquals(0L, observedMax(-10L, 3, 5, 1_000L), "a negative base counts as zero");
    }

    /** Sweeps the inputs to show the contract "always inside [0, cap]" holds everywhere. */
    @Test
    void everyCombinationStaysInsideTheCap() {
        for (long base : new long[] {-1L, 0L, 1L, 250L, 60_000L}) {
            for (int multiple : new int[] {-3, 0, 1, 2, 7}) {
                for (int attempt : new int[] {-2, 0, 1, 4}) {
                    for (long cap : new long[] {1L, 9L, 1_000L, 86_400_000L}) {
                        // The sweep only needs the contract, not the window top: 64 draws keep it fast.
                        for (long delay : samples(base, multiple, attempt, cap, 64)) {
                            assertTrue(delay >= 0L && delay <= cap,
                                    "base=" + base + " multiple=" + multiple + " attempt=" + attempt
                                            + " cap=" + cap + " produced " + delay);
                        }
                    }
                }
            }
        }
    }

    private static long observedMax(long base, int multiple, int attempt, long cap) {
        return samples(base, multiple, attempt, cap).stream().mapToLong(Long::longValue).max().orElseThrow();
    }

    private static long observedMin(long base, int multiple, int attempt, long cap) {
        return samples(base, multiple, attempt, cap).stream().mapToLong(Long::longValue).min().orElseThrow();
    }

    private static List<Long> samples(long base, int multiple, int attempt, long cap) {
        return samples(base, multiple, attempt, cap, SAMPLES);
    }

    private static List<Long> samples(long base, int multiple, int attempt, long cap, int count) {
        List<Long> delays = new ArrayList<>(count);
        for (int i = 0; i < count; i++) {
            delays.add(McpBackoff.resolveDelayMillis(base, multiple, attempt, cap));
        }
        return delays;
    }
}
