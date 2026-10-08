package com.summit.kernel.tools.search;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.summit.core.mcp.McpToolScope;
import com.summit.core.tool.ToolDefinition;
import com.summit.core.tool.ToolExecuteResult;
import com.summit.core.tool.ToolExecution;
import com.summit.core.tool.ToolExecutor;
import com.summit.core.tool.ToolRegistry;
import lombok.NonNull;

import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.function.Supplier;

/**
 * Finds the tools callable in this run by keyword, returns them flat, and discloses the remote ones.
 *
 * <p>Two sources: the process-wide {@link ToolRegistry} and this request's {@link McpToolScope}.
 * Remote tool names only exist after the server handshake, so they cannot be pre-declared in the
 * request's tool list — the model looks one up here instead.
 *
 * <p>The name-and-schema pair is the second disclosure step: a hit is recorded on the scope and the
 * tool becomes callable from the next model round on. Static tools pass through the same call and
 * are ignored by the ledger, being already declared.
 */
public class SearchToolExecutor implements ToolExecutor {

    /** Tool name; referenced by the assembled prompt, so a replacement must keep it. */
    public static final String NAME = "search_tool";

    private static final int MIN_MATCHES = 1;

    private final ObjectMapper objectMapper;
    /** Resolved lazily: the registry holds this tool, so an eager reference would close a cycle. */
    private final Supplier<ToolRegistry> toolRegistry;
    private final int maxMatches;

    public SearchToolExecutor(ObjectMapper objectMapper, Supplier<ToolRegistry> toolRegistry, int maxMatches) {
        this.objectMapper = objectMapper;
        this.toolRegistry = toolRegistry;
        this.maxMatches = Math.max(maxMatches, MIN_MATCHES);
    }

    @Override
    public @NonNull ToolExecuteResult execute(ToolExecution toolExecution) {
        try {
            return search(toolExecution);
        } catch (Exception e) {
            return ToolExecuteResult.err("search_tool failed: " + e);
        }
    }

    private ToolExecuteResult search(ToolExecution toolExecution) throws JsonProcessingException {
        String keyword = keywordOf(toolExecution).toLowerCase(Locale.ROOT).strip();

        List<ToolDefinition<?>> matched = callableTools(toolExecution).values().stream()
                .filter(tool -> matches(tool, keyword))
                .sorted(Comparator.comparing(ToolDefinition::name))
                .toList();

        // Disclose before answering: the model may call any returned remote tool on its next turn.
        toolExecution.requireMcpToolScope().disclose(matched.stream().map(ToolDefinition::name).toList());

        List<SearchedTool> tools = matched.stream()
                .limit(maxMatches)
                .map(tool -> new SearchedTool(tool.name(), normalize(tool.description()),
                        normalize(tool.parametersJsonSchema()), tool.readOnly()))
                .toList();

        SearchToolResult result = new SearchToolResult(
                keyword,
                matched.size(),
                tools.size(),
                tools);
        return ToolExecuteResult.success(objectMapper.writeValueAsString(result));
    }

    /**
     * The tools callable in this run: the static registry unioned with this request's MCP scope. On
     * a name collision the static tool wins, matching the model request's visibility rule.
     */
    private Map<String, ToolDefinition<?>> callableTools(ToolExecution toolExecution) {
        Map<String, ToolDefinition<?>> tools = new LinkedHashMap<>();

        // The registry is optional: a run without one falls back to its own MCP scope instead of
        // failing, which is also how the visibility test exercises the MCP layer alone.
        ToolRegistry registry = toolRegistry.get();
        if (registry != null && registry.getTools() != null) {
            tools.putAll(registry.getTools());
        }

        McpToolScope scope = toolExecution.requireMcpToolScope();
        scope.getTools().forEach(tool -> tools.putIfAbsent(tool.name(), tool));

        return tools;
    }

    private static boolean matches(ToolDefinition<?> tool, String keyword) {
        if (keyword.isEmpty()) {
            return true;
        }
        if (tool.name().toLowerCase(Locale.ROOT).contains(keyword)) {
            return true;
        }
        String description = tool.description();
        return description != null && description.toLowerCase(Locale.ROOT).contains(keyword);
    }

    /**
     * Missing arguments, blank arguments, an empty object and a missing keyword all mean "list
     * everything": a malformed argument must not turn a lookup into a failed round.
     */
    private String keywordOf(ToolExecution toolExecution) {
        String args = toolExecution.getArgs();
        if (args == null || args.isBlank()) {
            return "";
        }
        JsonNode argument;
        try {
            argument = objectMapper.readTree(args);
        } catch (JsonProcessingException malformed) {
            return "";
        }
        if (argument == null || !argument.isObject()) {
            return "";
        }
        JsonNode keyword = argument.get("keyword");
        return keyword == null || keyword.isNull() ? "" : keyword.asText("");
    }

    private static String normalize(String text) {
        return text == null ? "" : text.strip().replaceAll("\\s+", " ");
    }

    /** Body of one search. {@code keyword} is echoed back so a follow-up narrows from a known state. */
    private record SearchToolResult(
            String keyword,
            int total,
            int returned,
            List<SearchedTool> tools
    ) {
    }

    /** A hit including its schema — the schema is what makes the tool callable next round. */
    private record SearchedTool(String name, String description, String parameters, boolean readOnly) {
    }
}
