package com.summit.core.audit.token;

import com.summit.core.audit.DocumentElement;

/**
 * Context estimates and attribution details, not provider-billed usage.
 *
 * <p>Per-round context — remote-tool résumés, skills, memory and the task manifest — travels as a
 * user message, so it is reported here instead of being folded into {@code systemTokens} or
 * {@code userTokens}. {@code memoryTokens} is the memory slice of it, kept for callers that only
 * care about that part; it is therefore included in {@code dynamicContextTokens}, not additive to
 * it. Non-text user attachments are excluded from {@code userTokens} and estimated separately when
 * supported by the estimator.</p>
 */
public record OtherTokens(
        DocumentElement<Integer> toolDefinitionTokens,
        DocumentElement<Integer> mcpToolDefinitionTokens,
        /** Whole per-round context block: heading, MCP résumés, skills, memory and task manifest. */
        DocumentElement<Integer> dynamicContextTokens,
        /** Joined memory bodies, excluding the section heading and message framing. */
        DocumentElement<Integer> memoryTokens,
        /** Non-text user content retained in the current execution, excluding message framing. */
        DocumentElement<Integer> attachmentTokens
) {
}

