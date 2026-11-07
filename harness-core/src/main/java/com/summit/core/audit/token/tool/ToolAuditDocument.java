package com.summit.core.audit.token.tool;

import com.summit.core.audit.DocumentElement;

import java.util.Map;

/**
 * Audit of calls with retained results. Totals exclude arguments already counted in assistant messages.
 * Each detail is estimated independently, so request framing and tokenization boundaries can make
 * the sum of group totals differ from the whole-batch total.
 */
public record ToolAuditDocument(
        int toolTokens,
        DocumentElement<ToolElement> mcpDetail,
        DocumentElement<ToolElement> skillDetail,
        Map<String,ToolElement> tokenDetails  // Ordinary tool name -> estimates; MCP and SKILL have separate totals
) {

}
