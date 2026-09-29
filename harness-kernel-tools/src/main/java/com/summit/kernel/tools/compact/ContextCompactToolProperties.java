package com.summit.kernel.tools.compact;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Settings of context compaction: the tool that asks the model to summarize, and the multi-stage
 * thresholds that decide when compaction happens at all.
 *
 * <p>Both live here because they are one subject. The tool is what the model calls when it is told
 * to; the thresholds are what tells it to — splitting them across two namespaces meant the same
 * question ("when do we compress?") had two answers in two files.</p>
 *
 * <p>The tool is on by default: the field default was already {@code true} while the
 * auto-configuration only matched an explicit {@code enabled: true}, so the two disagreed and a
 * deployment that never wrote the key had no way to compact. The condition now follows the field.</p>
 */
@Data
@ConfigurationProperties(prefix = "lingxi.agent.runtime.tool.context-compact")
public class ContextCompactToolProperties {

    /** Whether the {@code compact_context} tool is registered at all. */
    private boolean enabled = true;

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
