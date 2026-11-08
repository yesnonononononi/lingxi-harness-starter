package com.summit.core.audit.token;

import com.summit.core.audit.DocumentElement;
import com.summit.core.audit.token.tool.ToolAuditDocument;

/**
 * Message-role estimates for the retained context. Tool arguments contribute to assistantTokens;
 * tool totals exclude them, while tool details retain argument counts for attribution only.
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
