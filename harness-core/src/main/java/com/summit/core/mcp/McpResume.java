package com.summit.core.mcp;

/**
 * Prompt summary of one MCP tool: its name, its description, and the server it came from.
 *
 * <p>A résumé, not a definition: the parameter schema stays out of the prompt and is fetched on
 * demand through {@code search_tool}.
 */
public record McpResume(
        String name,
        String description,
        String server
) {
}
