package com.summit.core.audit.token.tool;

/**
 * Token estimates for a tool group. Total counts framed result messages, while outputTokens
 * counts the output text alone. Arguments are attribution only and are excluded from totalTokens.
 */
public record ToolElement(
        int argumentTokens,
        int outputTokens,
        int totalTokens
) {
}
