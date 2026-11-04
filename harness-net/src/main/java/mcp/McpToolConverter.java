package mcp;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.summit.core.mcp.McpToolExecutor;
import com.summit.core.tool.ToolDefinition;
import io.modelcontextprotocol.spec.McpSchema;
import lombok.extern.slf4j.Slf4j;

import java.util.Map;
import java.util.Objects;

/**
 * Turns the tool a server published into the harness {@link ToolDefinition}.
 *
 * <p>The published name and identifier are {@code prefix + <server tool name>}, while the executor
 * keeps the server's own name: the prefix exists to namespace the model-facing function, not to
 * rename the remote call. One {@link McpToolInvoker} instance is created per remote tool, so the
 * remote name is fixed at assembly time and can never be confused with a later definition.</p>
 */
@Slf4j
public final class McpToolConverter {

    /** Fallback schema of a tool that declares none; the protocol requires an object schema. */
    private static final Map<String, Object> EMPTY_SCHEMA = Map.of("type", "object");

    private final McpToolOptions options;
    private final ObjectMapper json;

    /**
     * @param options publication parameters of the server being converted
     * @param json    mapper that renders the input schema back to JSON for the prompt
     */
    public McpToolConverter(McpToolOptions options, ObjectMapper json) {
        this.options = Objects.requireNonNull(options, "options must not be null");
        this.json = Objects.requireNonNull(json, "json must not be null");
    }

    /**
     * @param tool       tool as published by the server
     * @param connection live connection the tool belongs to; the invoker reads the current client
     *                   from it on every call, so a reconnect keeps working tools
     * @return the definition handed to the model
     */
    public ToolDefinition<McpToolExecutor> convert(McpSchema.Tool tool, McpResilientConnection connection) {
        if (tool == null) {
            throw new IllegalArgumentException("MCP tool must not be null");
        }
        if (tool.name() == null || tool.name().isBlank()) {
            throw new IllegalArgumentException("MCP tool without a name cannot be published");
        }
        String publishedName = options.namePrefix() + tool.name();
        return ToolDefinition.<McpToolExecutor>builder()
                .id(publishedName)
                .name(publishedName)
                .description(tool.description())
                .parametersJsonSchema(resolveParametersJsonSchema(tool))
                .executor(new McpToolInvoker(connection, tool.name()))
                .maxOutput(options.maxOutput())
                .timeout(options.runtimeTimeoutSeconds())
                .concurrentPolicy(options.concurrentPolicy())
                .build();
    }

    /**
     * Renders the input schema the way the prompt needs it: a JSON object as text.
     *
     * @param tool tool as published by the server
     * @return the JSON schema of the tool's parameters, never null
     */
    public String resolveParametersJsonSchema(McpSchema.Tool tool) {
        Map<String, Object> schema = tool.inputSchema() == null || tool.inputSchema().isEmpty()
                ? EMPTY_SCHEMA
                : tool.inputSchema();
        try {
            return json.writeValueAsString(schema);
        } catch (JsonProcessingException failure) {
            // A schema that cannot be rendered would silently publish an uncallable tool.
            log.warn("MCP tool {} parameters cannot be rendered as JSON ({})",
                    tool.name(), failure.getClass().getSimpleName());
            throw new IllegalStateException(
                    "Failed to serialize the parameters of MCP tool '" + tool.name() + "'", failure);
        }
    }
}
