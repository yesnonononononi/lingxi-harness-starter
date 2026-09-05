package com.summit.core.compact;

/**
 * Context usage metric: the token count of the session messages relative to the configured context cap.
 *
 * <p>Computed by {@link Tokenizer#count} and {@link Tokenizer#calcCurrentTokenRatio} and shipped with
 * the context-update events so the front-end can render the usage state (e.g. a gauge).</p>
 *
 * @param tokenCount current context token count
 * @param maxTokens  configured context token cap
 * @param ratio      usage ratio, usually in [0,1]; may exceed 1 when over the cap
 */
public record ContextUsageMetric(
        int tokenCount,
        int maxTokens,
        double ratio
) {
}
