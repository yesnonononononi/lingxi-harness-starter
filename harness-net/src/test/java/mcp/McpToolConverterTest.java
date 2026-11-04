package mcp;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.summit.core.conf.McpConfig;
import com.summit.core.conf.McpTransport;
import com.summit.core.tool.ConcurrentPolicy;
import com.summit.core.tool.ToolDefinition;
import com.summit.core.tool.ToolExecuteResult;
import com.summit.core.tool.ToolExecution;
import io.modelcontextprotocol.client.McpSyncClient;
import io.modelcontextprotocol.spec.McpSchema;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The published tool must be prefixed while the remote call must not be.
 *
 * <p>Both halves are asserted here: the definition a model sees ({@code mcp_search}) and the request
 * the server receives ({@code search}) — the second one is exactly what the retired draft got wrong
 * when it used the definition name as the remote name.</p>
 */
class McpToolConverterTest {

    private static final String PREFIX = "mcp_";
    private static final Duration EXEC = Duration.ofMillis(1_500);

    private final ObjectMapper json = new ObjectMapper();

    @Test
    void publishesThePrefixAndKeepsTheRemoteName() {
        FakeMcpTransport transport = new FakeMcpTransport();
        McpConfig.MCP server = server(1_000, EXEC);
        McpResilientConnection connection = connectionOver(transport, server);
        try {
            ToolDefinition<?> tool = converterOf(PREFIX, server).convert(remoteTool("search"), connection);

            assertEquals("mcp_search", tool.name());
            assertEquals("mcp_search", tool.id());
            assertEquals("Search things", tool.description());
            assertEquals(1_000, tool.maxOutput());
            assertEquals(7L, tool.timeout(), "ceil(1500ms) + 5 grace seconds");
            assertEquals(ConcurrentPolicy.SERIAL_MUTATION, tool.concurrentPolicy());
            assertInstanceOf(McpToolInvoker.class, tool.executor());
            assertTrue(tool.parametersJsonSchema().contains("\"q\""));
        } finally {
            connection.close();
        }
    }

    /** Two servers of one application must be able to publish tools of the same name. */
    @Test
    void aServerSpecificPrefixKeepsToolsApart() {
        McpConfig.MCP server = server(1_000, EXEC);
        McpResilientConnection connection = connectionOver(new FakeMcpTransport(), server);
        try {
            ToolDefinition<?> tool = converterOf("mcp_github_", server).convert(remoteTool("search"), connection);

            assertEquals("mcp_github_search", tool.name());
            assertEquals("mcp_github_search", tool.id());
        } finally {
            connection.close();
        }
    }

    @Test
    void callingAToolUsesTheRemoteNameAndTheModelArguments() throws IOException {
        FakeMcpTransport transport = new FakeMcpTransport();
        McpConfig.MCP server = server(1_000, EXEC);
        McpResilientConnection connection = connectionOver(transport, server);
        try {
            ToolDefinition<?> tool = converterOf(PREFIX, server).convert(remoteTool("search"), connection);

            ToolExecuteResult result = tool.executor().execute(ToolExecution.builder()
                    .id("call-1").toolDefinition(tool).args("{\"q\":\"hello\"}").build());

            assertTrue(result.isSuccess(), result.getToolOutput());
            assertEquals("ok", result.getToolOutput());
            Map<String, Object> call = transport.receivedToolCalls().getFirst();
            assertEquals("search", call.get("name"));
            assertInstanceOf(Map.class, call.get("arguments"));
            assertEquals("hello", ((Map<?, ?>) call.get("arguments")).get("q"));
        } finally {
            connection.close();
        }
    }

    /** A tool without parameters still sends an object, as the protocol requires. */
    @Test
    void blankArgumentsBecomeAnEmptyObject() {
        FakeMcpTransport transport = new FakeMcpTransport();
        McpConfig.MCP server = server(1_000, EXEC);
        McpResilientConnection connection = connectionOver(transport, server);
        try {
            ToolDefinition<?> tool = converterOf(PREFIX, server).convert(remoteTool("search"), connection);

            tool.executor().execute(ToolExecution.builder().id("call-2").toolDefinition(tool).args("  ").build());

            Map<String, Object> call = transport.receivedToolCalls().getFirst();
            assertInstanceOf(Map.class, call.get("arguments"));
            assertTrue(((Map<?, ?>) call.get("arguments")).isEmpty());
        } finally {
            connection.close();
        }
    }

    @Test
    void theParameterSchemaIsTheSerializedInputSchema() throws IOException {
        McpSchema.Tool remote = remoteTool("search");
        String schema = converterOf(PREFIX, server(1_000, EXEC)).resolveParametersJsonSchema(remote);

        assertEquals(json.writeValueAsString(remote.inputSchema()), schema);
        assertTrue(schema.contains("\"q\""));
    }

    /** The protocol requires an object schema; a server that publishes none still gets one. */
    @Test
    void aToolWithoutSchemaGetsAnObjectSchema() {
        McpSchema.Tool remote = McpSchema.Tool.builder("bare", Map.of()).build();

        assertEquals("{\"type\":\"object\"}",
                converterOf(PREFIX, server(1_000, EXEC)).resolveParametersJsonSchema(remote));
    }

    @Test
    void isErrorBecomesAnErrorResultCarryingTheServerText() {
        McpToolInvoker invoker = invokerOf("search");

        ToolExecuteResult result = invoker.toResult(McpSchema.CallToolResult.builder(
                List.of(text("boom"))).isError(true).build());

        assertFalse(result.isSuccess());
        assertEquals("boom", result.getToolOutput());
    }

    @Test
    void structuredContentWinsOverText() {
        McpToolInvoker invoker = invokerOf("search");

        ToolExecuteResult result = invoker.toResult(McpSchema.CallToolResult.builder(List.of(text("ignored")))
                .structuredContent(Map.of("value", 42))
                .build());

        assertTrue(result.isSuccess());
        assertEquals("{\"value\":42}", result.getToolOutput());
    }

    @Test
    void multipleTextBlocksAreJoinedInOrder() {
        McpToolInvoker invoker = invokerOf("search");

        ToolExecuteResult result = invoker.toResult(McpSchema.CallToolResult.builder(
                List.of(text("first"), text("second"))).build());

        assertTrue(result.isSuccess());
        assertEquals("first\nsecond", result.getToolOutput());
    }

    @Test
    void anEmptyAnswerIsReportedAsSuch() {
        McpToolInvoker invoker = invokerOf("search");

        ToolExecuteResult result = invoker.toResult(McpSchema.CallToolResult.builder(List.of()).build());

        assertFalse(result.isSuccess());
        assertEquals("mcp tool execute failed : empty result returned by MCP server", result.getToolOutput());
        assertFalse(invoker.toResult(null).isSuccess());
    }

    /** A given-up connection stays quiet and reports one stable message instead of throwing. */
    @Test
    void anUnavailableConnectionReportsAnError() {
        McpConfig.MCP server = server(1_000, EXEC);
        McpResilientConnection connection = connectionOver(new FakeMcpTransport(), server);
        connection.close();
        McpToolInvoker invoker = new McpToolInvoker(connection, "search", json);

        ToolExecuteResult result = invoker.execute(ToolExecution.builder()
                .id("call-3").args("{\"q\":\"x\"}").build());

        assertFalse(result.isSuccess());
        assertEquals("MCP server is unavailable: search", result.getToolOutput());
    }

    @Test
    void invalidArgumentsAreReportedInsteadOfThrown() {
        McpResilientConnection connection = connectionOver(new FakeMcpTransport(), server(1_000, EXEC));
        try {
            McpToolInvoker invoker = new McpToolInvoker(connection, "search", json);

            ToolExecuteResult result = invoker.execute(ToolExecution.builder()
                    .id("call-4").args("not-json").build());

            assertFalse(result.isSuccess());
            assertTrue(result.getToolOutput().startsWith("mcp tool execute failed : "), result.getToolOutput());
        } finally {
            connection.close();
        }
    }

    private McpToolInvoker invokerOf(String remoteName) {
        McpResilientConnection connection = connectionOver(new FakeMcpTransport(), server(1_000, EXEC));
        try {
            return new McpToolInvoker(connection, remoteName, json);
        } finally {
            connection.close();
        }
    }

    private McpToolConverter converterOf(String prefix, McpConfig.MCP server) {
        return new McpToolConverter(McpToolOptions.withPrefix(prefix, server), json);
    }

    private McpResilientConnection connectionOver(FakeMcpTransport transport, McpConfig.MCP server) {
        AtomicInteger closes = new AtomicInteger();
        McpSyncClient client = McpSessionFactory.connect(transport, McpClientSettings.defaults(), server);
        return McpTestSupport.connectionOf(() -> new McpTestSupport.CountingHandle(client, closes));
    }

    private static McpSchema.Tool remoteTool(String name) {
        return McpSchema.Tool.builder(name, Map.of("type", "object",
                        "properties", Map.of("q", Map.of("type", "string")),
                        "required", List.of("q")))
                .description("Search things")
                .build();
    }

    private static McpConfig.MCP server(int maxOutput, Duration executionTimeout) {
        return new McpConfig.MCP("fake", "fake server", McpTransport.STREAMABLE_HTTP,
                new McpConfig.StreamableHttp("https://fake.example/mcp", Map.of(),
                        Duration.ofSeconds(3), executionTimeout), maxOutput);
    }

    private static McpSchema.Content text(String value) {
        return McpSchema.TextContent.builder(value).build();
    }
}
