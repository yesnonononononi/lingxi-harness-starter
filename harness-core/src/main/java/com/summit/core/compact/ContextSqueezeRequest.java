package com.summit.core.compact;

import lombok.Builder;

/**
 * Decision result of the two-band context squeeze policy:
 * <ul>
 *   <li>when {@link #shouldSqueeze()} is {@code true} the caller runs the local round-based
 *       truncation, processing at most {@link #truncateTurn()} rounds;</li>
 *   <li>when {@link #expectAdvanceSqueeze()} is {@code true} the token ratio has reached the
 *       model-squeeze threshold, so the caller runs the model-based deep compaction
 *       (e.g. the {@code compact_context} tool).</li>
 * </ul>
 *
 * <p>The two bands are mutually exclusive: the local band covers
 * {@code [truncateThreshold, modelThreshold)} and the model band covers
 * {@code [modelThreshold, +inf)}. Which band a given ratio falls into is decided by the caller
 * (the runtime boundary checker); this record only carries the decision.</p>
 */
@Builder
public record ContextSqueezeRequest(

        boolean shouldSqueeze,

        /* Number of oldest rounds the local truncation squeezes in this pass. */
        Integer truncateTurn,

        /* Whether the model-based deep compaction band has been reached. */
        boolean expectAdvanceSqueeze
) {

}
