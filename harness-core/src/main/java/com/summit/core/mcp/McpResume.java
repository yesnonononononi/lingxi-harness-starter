package com.summit.core.mcp;

/**
 * Prompt summary of one connected MCP server: its name, the description it published during
 * initialization, and how many tools it contributed to this request.
 *
 * <p>A résumé, not a definition: neither the tools nor their parameter schemas appear in the
 * prompt. The first level of progressive disclosure publishes these counts, {@code list_mcp_tools}
 * turns a count into tool names and descriptions, and {@code search_tool} returns one schema.</p>
 *
 * @param name        server name as declared by the request configuration
 * @param description the {@code instructions} the server returned while initializing, or an empty
 *                    string when it published none
 * @param toolCount   number of tools this server contributed to the request
 */
public record McpResume(
        String name,
        String description,
        Integer toolCount
) {
}
