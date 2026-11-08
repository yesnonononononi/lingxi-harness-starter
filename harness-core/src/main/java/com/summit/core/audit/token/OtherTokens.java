package com.summit.core.audit.token;

import com.summit.core.audit.DocumentElement;

public record OtherTokens(
        DocumentElement<Integer> toolDefinitionTokens,
        DocumentElement<Integer> mcpToolDefinitionTokens
) {
}
