package com.summit.core.compact;

/**
 * Session-context compaction SPI: the implementation owns the full "how", the caller only decides "when".
 *
 * <p>Two implementations exist, one per squeeze band:</p>
 * <ul>
 *   <li>{@code DefaultManualCompacter}: local per-round truncation (no model), for the
 *       {@link ContextSqueezeRequest#shouldSqueeze()} band;</li>
 *   <li>{@code DefaultModelCompacter}: summarizes the session with a compact model and rebuilds it,
 *       for the {@link ContextSqueezeRequest#expectAdvanceSqueeze()} band.</li>
 * </ul>
 *
 * <p>Both are blocking: once the runtime checkpoint decides a squeeze, the compacter for the band is
 * invoked synchronously via {@link #compact(ContextCompactRequest)} and only then does the next agent
 * loop round start.</p>
 */
public interface ContextCompacter {

    /**
     * Performs one blocking compaction of the requested session.
     *
     * @param request the compaction request (session, execution context and triggering band decision)
     * @return {@code true} if a compaction actually ran (conversation context rewritten);
     *         {@code false} if nothing was needed or it failed
     */
    boolean compact(ContextCompactRequest request);
}
