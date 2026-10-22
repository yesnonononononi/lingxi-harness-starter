package com.summit.kernel.tools.compact;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Automatic compaction thresholds, independent of the request's tool grants.
 */
@Data
@ConfigurationProperties(prefix = "lingxi.agent.runtime.tool.context-compact")
public class ContextCompactToolProperties {

    /**
     * First stage: at this context-to-budget ratio the oldest tool rounds start being truncated
     * locally, without calling a model.
     *
     * <p>The cheap stage. Truncation loses detail, so it starts well before the budget is tight,
     * giving the run room to finish on its own before anything is summarized away.</p>
     */
    private double truncateThreshold = 0.7;

    /**
     * Second stage: at this ratio truncation stops and the history is summarized by a model.
     *
     * <p>The expensive stage, and the last resort — a summary is lossy in a way truncation is not,
     * so it is only worth paying for when the cheap stage can no longer keep up. Must be above
     * {@link #truncateThreshold}: the two are read together as one band
     * ({@code [truncateThreshold, modelThreshold)} truncates, {@code >= modelThreshold} summarizes),
     * so an inverted pair leaves the first stage permanently unreachable.</p>
     */
    private double modelThreshold = 0.85;

    /**
     * Tool rounds the local stage processes per pass.
     *
     * <p>Bounds how much one truncation pass removes. Too large and a single checkpoint discards
     * context the run still needed; too small and the ratio barely moves before the next check.
     * Values below one fall back to the runtime's own default.</p>
     */
    private int truncateRounds = 5;
}
