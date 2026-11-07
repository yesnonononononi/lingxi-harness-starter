package com.summit.core.audit.token.tool;

/**
 * Text token estimates for a tool group. Total includes arguments and framed result messages,
 * while outputTokens counts the output text alone.
 */
public record ToolElement(
        int argumentTokens,
        int outputTokens,
        int totalTokens
) {
}
