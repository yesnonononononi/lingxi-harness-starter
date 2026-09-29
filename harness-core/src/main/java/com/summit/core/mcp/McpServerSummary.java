package com.summit.core.mcp;

/**
 * Prompt summary of one connected MCP server: its name and how many tools it contributed.
 *
 * @param name  server name as declared by the request configuration
 * @param count number of tools this server contributed to the request
 */
public record McpServerSummary(
        String name,
        int count
) {
}
