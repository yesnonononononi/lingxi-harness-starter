package com.summit.core.audit.token.tool;

import com.summit.core.audit.DocumentElement;

import java.util.Map;

/**
 * The total includes all retained call arguments and tool result messages. Group details cover calls
 * with retained results. Request framing and tokenization boundaries can also make group totals
 * differ from the whole-batch total.
 */
public record ToolAuditDocument(
        int toolTokens,
        DocumentElement<ToolElement> mcpDetail,
        DocumentElement<ToolElement> skillDetail,
        Map<String,ToolElement> tokenDetails  // Ordinary tool name -> estimates; MCP and SKILL have separate totals
) {

}
