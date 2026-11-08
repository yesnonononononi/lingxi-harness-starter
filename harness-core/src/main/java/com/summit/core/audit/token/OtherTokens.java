package com.summit.core.audit.token;

import com.summit.core.audit.DocumentElement;

/**
 * Context estimates and attribution details, not provider-billed usage.
 * Memory bodies are already included in systemTokens; user attachments are already included
 * in userTokens when supported by the estimator. Neither detail should be added to role totals.
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
