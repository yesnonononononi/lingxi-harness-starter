package com.summit.core.audit.token;

import com.summit.core.audit.DocumentElement;
import com.summit.core.audit.token.tool.ToolAuditDocument;

/**
 * Message-role estimates and tool attribution for the retained context. Tool arguments also
 * contribute to assistantTokens, so these two views overlap on the argument text.
 */
public record TokenAuditDocument(
        DocumentElement<Integer> assistantTokens,
        DocumentElement<Integer> userTokens,
        DocumentElement<ToolAuditDocument> toolTokens,
        DocumentElement<Integer> systemTokens
) {
}
