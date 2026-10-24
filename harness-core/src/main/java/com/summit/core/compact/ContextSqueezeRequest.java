package com.summit.core.compact;

/**
 * Decision result of the context squeeze policy: which compaction band, if any, the checkpoint
 * should run.
 *
 * <p>The local round-based truncation band is gone; what remains is the summarize band and the
 * prune band that backs it up. Which one applies depends on the token ratio <em>and</em> on the
 * compaction state of the execution — summarization is attempted first and, once it is closed,
 * pruning rechecks the growing context at later checkpoints:</p>
 *
 * <ul>
 *   <li>{@link SqueezeBand#NONE}: nothing to do. Either the ratio is under the model threshold, or
 *       no checkpoint action is needed;</li>
 *   <li>{@link SqueezeBand#SUMMARIZE}: the ratio reached the model threshold and summarization is
 *       still available — the caller runs the model compaction;</li>
 *   <li>{@link SqueezeBand#PRUNE}: summarization is closed for this execution and the context
 *       is over the trigger threshold — the caller prunes the local history instead.</li>
 * </ul>
 *
 * <p>This record only carries the decision; the checkpoint performs the band.</p>
 *
 * @param band the band the checkpoint should run
 */
public record ContextSqueezeRequest(SqueezeBand band) {

    /** The compaction bands a checkpoint can pick. */
    public enum SqueezeBand {
        /** No compaction is needed. */
        NONE,
        /** Model summarization: the history is compressed into a summary and rebuilt from it. */
        SUMMARIZE,
        /** Local pruning: historical skeletons, then text-only history if necessary. */
        PRUNE
    }

    public ContextSqueezeRequest {
        if (band == null) {
            throw new IllegalArgumentException("band must not be null");
        }
    }

    /** @return a decision that asks for no compaction */
    public static ContextSqueezeRequest none() {
        return new ContextSqueezeRequest(SqueezeBand.NONE);
    }

    /** @return a decision that asks for model summarization */
    public static ContextSqueezeRequest summarize() {
        return new ContextSqueezeRequest(SqueezeBand.SUMMARIZE);
    }

    /** @return a decision that asks for local pruning */
    public static ContextSqueezeRequest prune() {
        return new ContextSqueezeRequest(SqueezeBand.PRUNE);
    }

    /** @return whether a band was selected */
    public boolean shouldCompact() {
        return band != SqueezeBand.NONE;
    }
}
