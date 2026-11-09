package com.summit.core.audit.token.tool;

/**
 * Token estimates for a tool group. The total counts framed result messages and their call
 * arguments, while outputTokens counts the output text alone.
 */
public record ToolElement(
        int argumentTokens,
        int outputTokens,
        int totalTokens
) {
}
