package com.summit.kernel.tools.mcp;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.summit.core.mcp.McpToolScope;
import com.summit.core.tool.ToolDefinition;
import com.summit.core.tool.ToolExecuteResult;
import com.summit.core.tool.ToolExecution;
import com.summit.core.tool.ToolExecutor;
import lombok.NonNull;

import java.util.List;

/**
 * Lists the tools one MCP server contributed to this run, by name and description.
 *
 * <p>The inventory half of progressive disclosure: the prompt publishes only a per-server count,
 * this turns that count into names, and {@link com.summit.kernel.tools.search.SearchToolExecutor}
 * then fetches the schema of a chosen tool. It returns no schema, so calling it never makes a tool
 * callable.
 */
public class ListMcpToolsExecutor implements ToolExecutor {

    /** Tool name; referenced by the assembled prompt, so a replacement must keep it. */
    public static final String NAME = "list_mcp_tools";

    private static final int MIN_LIMIT = 1;

    private final ObjectMapper objectMapper;
    private final int maxTools;

    public ListMcpToolsExecutor(ObjectMapper objectMapper, int maxTools) {
        this.objectMapper = objectMapper;
        this.maxTools = Math.max(maxTools, MIN_LIMIT);
    }

    @Override
    public @NonNull ToolExecuteResult execute(ToolExecution toolExecution) {
        try {
            return list(toolExecution);
        } catch (Exception e) {
            return ToolExecuteResult.err("list_mcp_tools failed: " + e);
        }
    }

    private ToolExecuteResult list(ToolExecution toolExecution) throws JsonProcessingException {
        JsonNode argument = argumentOf(toolExecution);
        String requested = textOf(argument, "mcpName", "name", "server");
        int limit = limitOf(argument);
        McpToolScope scope = toolExecution.requireMcpToolScope();

        List<ToolDefinition<?>> matched = scope.toolsOfServer(requested);
        boolean unknownServer = !requested.isEmpty() && matched.isEmpty();

        List<ListedTool> tools = matched.stream()
                .limit(limit)
                .map(tool -> new ListedTool(tool.name(), normalize(tool.description())))
                .toList();

        ListMcpToolsResult result = new ListMcpToolsResult(
                requested.isEmpty() ? null : requested,
                matched.size(),
                tools.size(),
                tools,
                // Only worth naming the servers when the caller gave no name or gave a wrong one.
                requested.isEmpty() || unknownServer ? scope.serverNames() : null);
        return ToolExecuteResult.success(objectMapper.writeValueAsString(result));
    }

    private int limitOf(JsonNode argument) {
        JsonNode limit = argument == null ? null : argument.get("limit");
        if (limit == null || !limit.isNumber()) {
            return maxTools;
        }
        int requested = limit.asInt(maxTools);
        return requested < MIN_LIMIT ? maxTools : Math.min(requested, maxTools);
    }

    /**
     * The parsed argument object, or {@code null} when missing or malformed: a faulty argument must
     * not turn an inventory into a failed round.
     */
    private JsonNode argumentOf(ToolExecution toolExecution) {
        String args = toolExecution.getArgs();
        if (args == null || args.isBlank()) {
            return null;
        }
        try {
            JsonNode argument = objectMapper.readTree(args);
            return argument != null && argument.isObject() ? argument : null;
        } catch (JsonProcessingException malformed) {
            return null;
        }
    }

    /** The first non-blank value among the given keys, so a differently named argument still lands. */
    private static String textOf(JsonNode argument, String... keys) {
        if (argument == null) {
            return "";
        }
        for (String key : keys) {
            JsonNode value = argument.get(key);
            if (value != null && !value.isNull()) {
                String text = value.asText("").strip();
                if (!text.isEmpty()) {
                    return text;
                }
            }
        }
        return "";
    }

    private static String normalize(String text) {
        return text == null ? "" : text.strip().replaceAll("\\s+", " ");
    }

    /** Body of one listing. {@code servers} is present only when the request could not be resolved. */
    private record ListMcpToolsResult(
            String mcpName,
            int total,
            int returned,
            List<ListedTool> tools,
            List<String> servers
    ) {
    }

    private record ListedTool(String name, String description) {
    }
}
