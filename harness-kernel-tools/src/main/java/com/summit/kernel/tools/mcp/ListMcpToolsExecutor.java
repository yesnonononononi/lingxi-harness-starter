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
 *
 * <p>A listing is a window over the server's tools, expressed as a half-open range
 * {@code [start, end)} and ordered by name: a server may hold more tools than one window can
 * carry, and the caller walks it by asking for the next range. The window is capped by
 * {@code maxWindow} so one call cannot flood the context, and a request that names no range gets
 * the first {@code defaultWindow} tools.
 */
public class ListMcpToolsExecutor implements ToolExecutor {

    /** Tool name; referenced by the assembled prompt, so a replacement must keep it. */
    public static final String NAME = "list_mcp_tools";

    /** Windows are half-open; a size of zero would return nothing at all, so one is the floor. */
    private static final int MIN_WINDOW = 1;

    private final ObjectMapper objectMapper;
    private final int maxWindow;
    private final int defaultWindow;

    public ListMcpToolsExecutor(ObjectMapper objectMapper, int maxWindow, int defaultWindow) {
        this.objectMapper = objectMapper;
        this.maxWindow = Math.max(maxWindow, MIN_WINDOW);
        // A default wider than the cap would be silent dead configuration; clamp it instead.
        this.defaultWindow = Math.max(Math.min(defaultWindow, this.maxWindow), MIN_WINDOW);
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
        McpToolScope scope = toolExecution.requireMcpToolScope();

        // toolsOfServer already orders by name, so a window over it is stable across calls.
        List<ToolDefinition<?>> matched = scope.toolsOfServer(requested);
        boolean unknownServer = !requested.isEmpty() && matched.isEmpty();

        int total = matched.size();
        Window window = windowOf(argument, total);
        List<ListedTool> tools = matched.subList(window.start(), window.end()).stream()
                .map(tool -> new ListedTool(tool.name(), normalize(tool.description())))
                .toList();

        ListMcpToolsResult result = new ListMcpToolsResult(
                requested.isEmpty() ? null : requested,
                total,
                window.start(),
                window.end(),
                window.end() < total,
                tools,
                // Only worth naming the servers when the caller gave no name or gave a wrong one.
                requested.isEmpty() || unknownServer ? scope.serverNames() : null);
        return ToolExecuteResult.success(objectMapper.writeValueAsString(result));
    }

    /**
     * The half-open range this call reads, clamped to {@code [0, total]}.
     *
     * <p>Both ends are optional. A missing {@code start} means the first window; a missing
     * {@code end} extends {@code start} by the default window. Names are the only argument that
     * may be trusted, so every numeric value is coerced rather than validated: a listing must not
     * fail a round because the model sent a bad range.
     */
    private Window windowOf(JsonNode argument, int total) {
        int start = clamp(numberOf(argument, "start", 0), 0, total);
        int requestedEnd = numberOf(argument, "end", start + defaultWindow);
        // A range never exceeds the cap, and never inverts: end < start collapses to an empty
        // window rather than wrapping into the tail of the list.
        int end = clamp(requestedEnd, start, Math.min(start + maxWindow, total));
        return new Window(start, end);
    }

    private static int clamp(int value, int low, int high) {
        return Math.max(low, Math.min(value, high));
    }

    /** The named integer argument, or {@code fallback} when absent or not a number. */
    private static int numberOf(JsonNode argument, String key, int fallback) {
        JsonNode value = argument == null ? null : argument.get(key);
        return value == null || !value.isNumber() ? fallback : value.asInt(fallback);
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

    /** One read window, half-open: {@code start} is inclusive, {@code end} exclusive. */
    private record Window(int start, int end) {
    }

    /**
     * Body of one listing. {@code start} and {@code end} echo the window actually read, so the
     * caller can tell which slice of {@code total} it is looking at; {@code hasMore} says whether
     * another window follows. {@code servers} is present only when the request could not be
     * resolved.
     */
    private record ListMcpToolsResult(
            String mcpName,
            int total,
            int start,
            int end,
            boolean hasMore,
            List<ListedTool> tools,
            List<String> servers
    ) {
    }

    private record ListedTool(String name, String description) {
    }
}
