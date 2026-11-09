package com.summit.core.audit.token;

import com.summit.core.audit.DocumentElement;

/**
 * Context estimates and attribution details, not provider-billed usage.
 * Memory bodies are already included in systemTokens. Non-text user attachments are excluded
 * from userTokens and estimated separately when supported by the estimator.
 */
public record OtherTokens(
        DocumentElement<Integer> toolDefinitionTokens,
        DocumentElement<Integer> mcpToolDefinitionTokens,
        /** Joined memory bodies, excluding the section heading and message framing. */
        DocumentElement<Integer> memoryTokens,
        /** Non-text user content retained in the current execution, excluding message framing. */
        DocumentElement<Integer> attachmentTokens
) {
}
