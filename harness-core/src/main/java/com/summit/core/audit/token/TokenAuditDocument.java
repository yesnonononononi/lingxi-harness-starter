package com.summit.core.audit.token;

import com.summit.core.audit.DocumentElement;
import com.summit.core.audit.token.tool.ToolAuditDocument;

/**
 * Estimates for the retained context. Tool arguments contribute to toolTokens, and non-text user
 * attachments are reported separately in otherTokens.
 * Independently estimated role totals can differ from an estimate of the complete message batch.
 */
public record TokenAuditDocument(
        DocumentElement<Integer> assistantTokens,
        DocumentElement<Integer> userTokens,
        DocumentElement<ToolAuditDocument> toolTokens,
        DocumentElement<Integer> systemTokens,
        OtherTokens otherTokens
) {
}
