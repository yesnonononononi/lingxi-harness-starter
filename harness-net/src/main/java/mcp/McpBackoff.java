package mcp;

import java.util.concurrent.ThreadLocalRandom;

/**
 * Backoff of the reconnect attempts, kept as a pure function so the timing decision is testable
 * without a connection.
 *
 * <p>Mirrors the LingXi {@code RetryUtil}: the delay grows as {@code base * multiple^attempt},
 * saturates at {@code cap}, and is then jittered uniformly between zero and that saturated value.
 * The jitter matters — several connections that died at the same moment must not hammer the same
 * endpoint in lockstep.</p>
 */
public final class McpBackoff {

    /** Factor applied per attempt when the caller passes an unusable one. */
    private static final int MIN_MULTIPLE = 1;

    private McpBackoff() {
    }

    /**
     * Resolves one jittered delay.
     *
     * @param baseMillis first step of the progression in milliseconds; negative values count as
     *                   zero, which yields no wait
     * @param multiple   growth factor per attempt; values below one count as one, i.e. a constant
     *                   progression
     * @param attempt    zero-based attempt number; negative values count as zero
     * @param capMillis  upper bound of the progression in milliseconds; a non-positive cap yields
     *                   no wait
     * @return a delay in {@code [0, capped]}, never negative
     */
    public static long resolveDelayMillis(long baseMillis, int multiple, int attempt, long capMillis) {
        if (capMillis <= 0L) {
            return 0L;
        }
        long capped = Math.clamp(baseMillis, 0L, capMillis);
        int factor = Math.max(MIN_MULTIPLE, multiple);
        int steps = Math.max(0, attempt);
        for (int step = 0; step < steps && capped < capMillis; step++) {
            // Guard against overflow: once the next factor would exceed the cap, the cap wins.
            capped = capped > capMillis / factor ? capMillis : capped * factor;
        }
        return capped <= 0L ? 0L : ThreadLocalRandom.current().nextLong(capped + 1L);
    }
}
