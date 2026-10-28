package com.summit.core.agent;

/**
 * Immutable snapshot of how far the context compaction of one execution has progressed.
 *
 * <p>Two lifecycles live side by side and must not be conflated, which is exactly why they travel
 * as one value object instead of scattered fields:</p>
 *
 * <ul>
 *   <li>{@link #summaryAttempts()} counts the summarize attempts of the <b>current trigger
 *       window</b> (a long task may summarize many times, so it is not an execution-lifetime
 *       counter); it is only ever incremented <em>before</em> the summarize model is called, and
 *       reset to zero when the context is observed back in the safe zone;</li>
 *   <li>{@link #summaryUnavailable()} is an <b>execution-level</b> latch: once a summarize failed
 *       (exception, unusable output, no compact model configured, attempt budget spent while still
 *       over the threshold) the execution never summarizes again.</li>
 * </ul>
 *
 * <p><b>One writer per field</b> (design §3.3.4): the runtime boundary checker owns
 * {@code summaryAttempts} and {@code summaryUnavailable}; the conversation manager advances
 * {@code summaryGeneration} and resets {@code pruneGeneration}, while the pruner advances that
 * fallback stage. A rebuild must never reset the attempt counter or clear the unavailable
 * latch — doing so silently lifts the attempt ceiling of every execution.</p>
 *
 * @param summaryGeneration  number of successful summary generations; {@code 0} = original history,
 *                           {@code N} = the context was rebuilt from the N-th summary
 * @param summaryAttempts    summarize attempts spent in the current trigger window
 * @param summaryUnavailable whether summarization is closed for this execution
 * @param pruneGeneration    fallback stage of this summary generation: 0 = untouched,
 *                           1 = historical tool skeletons, 2 = historical assistant text only
 */
public record CompactionState(
        int summaryGeneration,
        int summaryAttempts,
        boolean summaryUnavailable,
        int pruneGeneration
) {

    /** The state of a context that was never compacted. */
    private static final CompactionState NONE = new CompactionState(0, 0, false, 0);

    public CompactionState {
        if (summaryGeneration < 0) {
            throw new IllegalArgumentException("summaryGeneration must not be negative: " + summaryGeneration);
        }
        if (summaryAttempts < 0) {
            throw new IllegalArgumentException("summaryAttempts must not be negative: " + summaryAttempts);
        }
        if (pruneGeneration < 0 || pruneGeneration > 2) {
            throw new IllegalArgumentException("pruneGeneration must be a stage in [0, 2]: " + pruneGeneration);
        }
    }

    /**
     * The state of a context that was never compacted.
     *
     * @return the initial state, safe to share because this type is immutable
     */
    public static CompactionState none() {
        return NONE;
    }

    // --- immutable per-field updates ---------------------------------------------------

    /**
     * Replaces the summary generation.
     *
     * @param value the new generation, zero or positive
     * @return a new state carrying that generation
     */
    public CompactionState withSummaryGeneration(int value) {
        return new CompactionState(value, summaryAttempts, summaryUnavailable, pruneGeneration);
    }

    /**
     * Replaces the attempt counter of the current trigger window.
     *
     * @param value the new attempt count, zero or positive
     * @return a new state carrying that count
     */
    public CompactionState withSummaryAttempts(int value) {
        return new CompactionState(summaryGeneration, value, summaryUnavailable, pruneGeneration);
    }

    /**
     * Replaces the execution-level "summarization is closed" latch.
     *
     * @param value the new latch value
     * @return a new state carrying that latch
     */
    public CompactionState withSummaryUnavailable(boolean value) {
        return new CompactionState(summaryGeneration, summaryAttempts, value, pruneGeneration);
    }

    /**
     * Replaces the fallback stage inside the current summary generation.
     *
     * @param value 0 for untouched, 1 for skeleton history, or 2 for text-only history
     * @return a new state carrying that stage
     */
    public CompactionState withPruneGeneration(int value) {
        return new CompactionState(summaryGeneration, summaryAttempts, summaryUnavailable, value);
    }

    // --- semantic transitions (one caller each) ----------------------------------------

    /**
     * Counts one summarize attempt. Written by the boundary checker <b>before</b> the summarize
     * model is invoked, so a throwing call is still counted.
     *
     * @return a new state with the attempt counter incremented by one
     */
    public CompactionState beforeSummaryAttempt() {
        return withSummaryAttempts(summaryAttempts + 1);
    }

    /**
     * Opens a new trigger window: the context observed after a successful summary is back below
     * the model threshold, so the attempt budget is available again.
     *
     * @return a new state with the attempt counter reset to zero
     */
    public CompactionState summaryWindowReset() {
        return withSummaryAttempts(0);
    }

    /**
     * Closes summarization for the rest of this execution. Idempotent, and never reverted.
     *
     * @return a new state with the unavailable latch set
     */
    public CompactionState summarizationUnavailable() {
        return withSummaryUnavailable(true);
    }

    /**
     * Records a successful context rebuild from a summary. Written by the conversation manager after
     * {@code rebuildContext} replaced the session messages.
     *
     * <p>The attempt counter and the unavailable latch are carried over untouched: a rebuild proves
     * nothing about the attempt budget, and resetting them here is the regression that lets an
     * execution summarize forever.</p>
     *
     * @return a new state with the summary generation advanced and the prune generation reset
     */
    public CompactionState afterSummaryRebuild() {
        return new CompactionState(summaryGeneration + 1, summaryAttempts, summaryUnavailable, 0);
    }

    /**
     * Advances the fallback stage inside the current context generation.
     *
     * @return a new state at the next stage, capped at text-only history
     */
    public CompactionState afterPrune() {
        return withPruneGeneration(Math.min(2, pruneGeneration + 1));
    }
}
