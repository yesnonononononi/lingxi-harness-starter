package mcp;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.summit.core.json.ExecutionJson;
import com.summit.core.mcp.McpToolExecutor;
import com.summit.core.tool.ToolExecuteResult;
import com.summit.core.tool.ToolExecution;
import io.modelcontextprotocol.client.McpSyncClient;
import io.modelcontextprotocol.spec.McpSchema;
import lombok.NonNull;
import lombok.extern.slf4j.Slf4j;

import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * Forwards one harness tool call to one tool of an MCP server.
 *
 * <p>One instance is bound to one remote tool, and the remote name is fixed at construction time.
 * That is deliberate: the published definition carries the prefix, so reading the name from
 * {@link ToolExecution#getToolDefinition()} would call a tool named {@code mcp_foo} on a server that
 * only knows {@code foo}.</p>
 *
 * <p>Nothing on this path throws. Timeout, output truncation and progress reporting belong to the
 * runtime; this class only decodes the arguments, calls the server and maps the answer — including
 * every failure — to {@link ToolExecuteResult}.</p>
 */
@Slf4j
public final class McpToolInvoker implements McpToolExecutor {

    /** Prefix of every failure this class reports, kept from the previous implementation. */
    private static final String FAILURE_MESSAGE = "mcp tool execute failed : ";

    /** Prefix of the failure of a connection that was given up; the tools stay published. */
    private static final String UNAVAILABLE_MESSAGE = "MCP server is unavailable: ";

    /** MCP expects the arguments as a JSON object, so a tool without parameters still gets {@code {}}. */
    private static final String EMPTY_ARGUMENTS = "{}";

    private static final TypeReference<Map<String, Object>> ARGUMENTS_TYPE = new TypeReference<>() {
    };

    private final McpResilientConnection connection;
    private final String remoteToolName;
    private final ObjectMapper json;

    /**
     * @param connection     connection the tool belongs to; the current client is read on every call
     * @param remoteToolName tool name as the server declared it, without any prefix
     */
    public McpToolInvoker(McpResilientConnection connection, String remoteToolName) {
        this(connection, remoteToolName, ExecutionJson.newObjectMapper());
    }

    /**
     * @param connection     connection the tool belongs to
     * @param remoteToolName tool name as the server declared it
     * @param json           mapper used to decode the model's arguments; the application's mapper
     *                       keeps that decoding consistent with the rest of the runtime
     */
    McpToolInvoker(McpResilientConnection connection, String remoteToolName, ObjectMapper json) {
        this.connection = Objects.requireNonNull(connection, "connection must not be null");
        this.remoteToolName = remoteToolName;
        this.json = json == null ? ExecutionJson.newObjectMapper() : json;
    }

    @Override
    public @NonNull ToolExecuteResult execute(ToolExecution toolExecution) {
        if (remoteToolName == null || remoteToolName.isBlank()) {
            return ToolExecuteResult.err(FAILURE_MESSAGE + "tool name is missing");
        }
        McpSchema.CallToolRequest request;
        try {
            request = McpSchema.CallToolRequest.builder(remoteToolName)
                    .arguments(resolveArguments(toolExecution == null ? null : toolExecution.getArgs()))
                    .build();
        } catch (RuntimeException failure) {
            log.warn("【ToolCall】 mcp {} arguments are invalid ({})",
                    remoteToolName, failure.getClass().getSimpleName());
            return ToolExecuteResult.err(FAILURE_MESSAGE + failure.getMessage());
        }

        McpSyncClient client;
        try {
            client = connection.client();
        } catch (RuntimeException failure) {
            // The connection was given up after its bounded reconnect attempts: report an error and
            // keep the tool published, so the model sees "server unavailable" instead of a vanished
            // function between two turns.
            log.warn("【ToolCall】 mcp {} skipped ({})", remoteToolName, failure.getMessage());
            return ToolExecuteResult.err(UNAVAILABLE_MESSAGE + remoteToolName);
        }

        try {
            McpSchema.CallToolResult result = client.callTool(request);
            return toResult(result);
        } catch (RuntimeException failure) {
            log.warn("【ToolCall】 mcp {} failed ({})", remoteToolName, failure.getClass().getSimpleName());
            return ToolExecuteResult.err(FAILURE_MESSAGE + failure.getMessage());
        }
    }

    /**
     * Maps one server answer to a harness result.
     *
     * @param result answer of the server, may be null
     * @return the result to hand back to the runtime, never null
     */
    ToolExecuteResult toResult(McpSchema.CallToolResult result) {
        if (result == null) {
            return ToolExecuteResult.err(FAILURE_MESSAGE + "empty result returned by MCP server");
        }
        String text = resolveText(result);
        if (Boolean.TRUE.equals(result.isError())) {
            // The server's own message is the only diagnosis worth surfacing; wrapping it would only
            // add the class name of a transport exception.
            log.warn("【ToolCall】 mcp server returned an error :{}", text);
            return ToolExecuteResult.err(text);
        }
        if (result.structuredContent() != null) {
            return ToolExecuteResult.success(resolveStructured(result.structuredContent()));
        }
        if (text.isEmpty()) {
            return ToolExecuteResult.err(FAILURE_MESSAGE + "empty result returned by MCP server");
        }
        return ToolExecuteResult.success(text);
    }

    /** @return the text blocks of one answer joined by newlines; never null */
    static String resolveText(McpSchema.CallToolResult result) {
        List<McpSchema.Content> content = result.content();
        if (content == null || content.isEmpty()) {
            return "";
        }
        StringBuilder text = new StringBuilder();
        for (McpSchema.Content item : content) {
            if (item instanceof McpSchema.TextContent textContent) {
                if (!text.isEmpty()) {
                    text.append('\n');
                }
                text.append(textContent.text());
            }
        }
        return text.toString();
    }

    /** @return the structured answer as JSON, or its own text when it already is a string */
    private String resolveStructured(Object structuredContent) {
        if (structuredContent instanceof String text) {
            return text;
        }
        try {
            return json.writeValueAsString(structuredContent);
        } catch (JsonProcessingException failure) {
            log.warn("MCP structured content cannot be rendered as JSON ({})",
                    failure.getClass().getSimpleName());
            return String.valueOf(structuredContent);
        }
    }

    /**
     * Decodes the model's arguments into the object the protocol expects.
     *
     * @param argumentsJson arguments as the model produced them, may be null or blank
     * @return the argument map; empty when the tool takes none
     */
    private Map<String, Object> resolveArguments(String argumentsJson) {
        if (argumentsJson == null || argumentsJson.isBlank()) {
            return Map.of();
        }
        if (EMPTY_ARGUMENTS.equals(argumentsJson.trim())) {
            return Map.of();
        }
        try {
            Map<String, Object> arguments = json.readValue(argumentsJson, ARGUMENTS_TYPE);
            return arguments == null ? Map.of() : arguments;
        } catch (JsonProcessingException failure) {
            throw new IllegalArgumentException("Invalid tool arguments JSON", failure);
        }
    }
}
