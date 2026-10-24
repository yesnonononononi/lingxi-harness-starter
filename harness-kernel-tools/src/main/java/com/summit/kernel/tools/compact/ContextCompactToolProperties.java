package com.summit.kernel.tools.compact;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Settings of context compaction: the tool that asks the model to summarize, the ratio that decides
 * when that happens, and the budget a local prune pass sizes the context against.
 *
 * <p>All of them live here because they are one subject. The tool is what the model calls when it is
 * told to; the ratios are what tells it to — splitting them across two namespaces meant the same
 * question ("when and how do we compress?") had two answers in two files.</p>
 *
 * <p>The local truncation band is gone: a checkpoint now answers with a <em>summary</em> (a model
 * call, gated by {@link #maxSummaryAttempts}) or with a <em>prune</em> (local, model-free, sized by
 * {@link #pruneTargetRatio}). Nothing here configures truncation any more, because there is no
 * truncation band left to configure.</p>
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
     * At this context-to-budget ratio the expensive band is entered: the history is summarized by a
     * model and the session is rebuilt from the summary.
     *
     * <p>The only ratio that still gates anything. Below it nothing happens; at or above it the
     * checkpoint first tries to summarize and, once summarization is closed for the execution (it
     * failed, or its attempt budget is spent while the context is still oversized), prunes the tool
     * history locally.</p>
     */
    private double modelThreshold = 0.85;

    /**
     * Share of the context cap a prune pass sizes its product against
     * ({@code pruneTargetRatio × maxTokens}).
     *
     * <p><b>Initial value 0.40, to be calibrated against real sessions</b> (design §6, D5). Pruning
     * never gives up because the non-cuttable part is large — it degrades every gradable segment as
     * far as the budget allows and reports the overflow — so this value steers how aggressive the
     * pass is, not whether it runs at all.</p>
     */
    private Double pruneTargetRatio = 0.40;

    /**
     * How many of the newest segments a prune pass may keep {@code FULL} (payload included).
     *
     * <p>The rest degrade to {@code SKELETON} (payload replaced by a one-line placeholder) and then
     * to {@code DROP} as the budget runs out (§3.6).</p>
     */
    private Integer fullSegmentCount = 8;

    /** Token cap applied to a single tool result kept inside a {@code FULL} segment. */
    private Integer fullToolOutputCap = 4_000;

    /**
     * Total summarize attempts of one trigger window: the first attempt plus its chained
     * re-compaction (design D1 fixes this at 2).
     *
     * <p>{@code null} means a single attempt — the framework deliberately carries no default here,
     * so "how many times may I pay for a summary" stays an application decision. Values {@code <= 0}
     * are rejected when the agent configuration is built.</p>
     */
    private Integer maxSummaryAttempts;
}
