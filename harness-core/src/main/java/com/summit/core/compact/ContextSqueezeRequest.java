package com.summit.core.compact;

import lombok.Builder;

/**
 * Decision result of the progressive context squeeze policy:
 * <ul>
 *   <li>when {@link #shouldSqueeze()} is {@code true} the caller runs the local
 *       round-based truncation, processing at most {@link #truncateTurn()} rounds;</li>
 *   <li>when {@link #expectAdvanceSqueeze()} is {@code true} the token ratio has
 *       reached the model-squeeze threshold, so the caller should let the model
 *       trigger a deep compaction (e.g. the {@code compact_context} tool).</li>
 * </ul>
 */
@Builder
public record ContextSqueezeRequest(
        boolean shouldSqueeze,

        /** Number of oldest rounds the local truncation squeezes in this pass. */
        Integer truncateTurn,

        boolean expectAdvanceSqueeze
) {

}
