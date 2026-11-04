package mcp;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.summit.core.conf.McpConfig;
import com.summit.core.conf.McpTransport;
import com.summit.core.tool.ToolExecuteResult;
import com.summit.core.tool.ToolExecution;
import io.modelcontextprotocol.client.McpSyncClient;
import io.modelcontextprotocol.json.TypeRef;
import io.modelcontextprotocol.spec.McpClientTransport;
import io.modelcontextprotocol.spec.McpSchema;
import org.junit.jupiter.api.Test;
import reactor.core.publisher.Mono;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Function;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Failure paths of {@link McpToolInvoker}: nothing on the tool-call path may escape as an exception,
 * and every failure must still name the tool.
 *
 * <p>These tests exist because the happy paths alone left the invoker's own guards unexecuted: a
 * blank remote name, a rejecting server, a string-valued structured result and a structured result
 * that cannot be rendered as JSON are all cases the model can trigger with an ordinary call.</p>
 */
class McpToolInvokerTest {

    private static final String FAILURE_PREFIX = "mcp tool execute failed : ";

    private final ObjectMapper json = new ObjectMapper();

    /** A tool whose remote name is unusable must fail before anything reaches the server. */
    @Test
    void blankOrMissingRemoteNameIsRejectedBeforeAnyRequest() {
        FakeMcpTransport transport = new FakeMcpTransport();
        McpResilientConnection connection = connectionOver(transport);
        try {
            for (String unusable : new String[] {"", "   ", null}) {
                ToolExecuteResult result = new McpToolInvoker(connection, unusable, json)
                        .execute(ToolExecution.builder().id("call-name").args("{\"q\":\"x\"}").build());

                assertNotNull(result, "the invoker must answer instead of returning null");
                assertFalse(result.isSuccess());
                assertEquals(FAILURE_PREFIX + "tool name is missing", result.getToolOutput());
            }
            assertTrue(transport.receivedToolCalls().isEmpty(),
                    "no request may be sent for a tool without a name");
        } finally {
            connection.close();
        }
    }

    /**
     * Without an injected mapper the invoker falls back to the execution mapper instead of keeping
     * the null, so argument decoding still works.
     */
    @Test
    void theFallbackMapperDecodesArgumentsWhenNoneIsInjected() {
        FakeMcpTransport transport = new FakeMcpTransport();
        McpResilientConnection connection = connectionOver(transport);
        try {
            ToolExecuteResult result = new McpToolInvoker(connection, "search", null)
                    .execute(ToolExecution.builder().id("call-map").args("{\"q\":\"hello\"}").build());

            assertTrue(result.isSuccess(), result.getToolOutput());
            assertEquals("ok", result.getToolOutput());
            assertEquals("hello", argumentsOf(transport).get("q"));
        } finally {
            connection.close();
        }
    }

    /** A server that rejects the call must produce an error result, never a thrown exception. */
    @Test
    void aRejectedToolCallIsReportedInsteadOfThrown() {
        McpResilientConnection connection = connectionOver(new RejectingTransport());
        try {
            ToolExecuteResult result = new McpToolInvoker(connection, "search", json)
                    .execute(ToolExecution.builder().id("call-reject").args("{\"q\":\"x\"}").build());

            assertNotNull(result, "a failed call must still be a result object");
            assertFalse(result.isSuccess());
            assertTrue(result.getToolOutput().startsWith(FAILURE_PREFIX), result.getToolOutput());
            assertTrue(result.getToolOutput().length() > FAILURE_PREFIX.length(),
                    "the server's own diagnosis must reach the model: " + result.getToolOutput());
        } finally {
            connection.close();
        }
    }

    /** A structured result that already is a string is handed over unchanged. */
    @Test
    void stringStructuredContentIsPassedThrough() {
        McpToolInvoker invoker = invokerOf();

        ToolExecuteResult result = invoker.toResult(McpSchema.CallToolResult.builder(List.of(text("ignored")))
                .structuredContent("plain text answer")
                .build());

        assertTrue(result.isSuccess(), result.getToolOutput());
        assertEquals("plain text answer", result.getToolOutput());
    }

    /**
     * A structured result that cannot be rendered as JSON degrades to its {@code toString()} form
     * instead of failing the call: the model gets something readable, not an error.
     */
    @Test
    void structuredContentThatCannotBeRenderedFallsBackToText() {
        McpToolInvoker invoker = invokerOf();
        Object unserializable = new Object();

        ToolExecuteResult result = invoker.toResult(McpSchema.CallToolResult.builder(List.of(text("ignored")))
                .structuredContent(Map.of("payload", unserializable))
                .build());

        assertTrue(result.isSuccess(), result.getToolOutput());
        assertEquals("{payload=" + String.valueOf(unserializable) + "}", result.getToolOutput());
        assertFalse(result.getToolOutput().isEmpty(), "the fallback must not degrade to an empty answer");
    }

    /** A missing execution is treated exactly like a tool that takes no arguments. */
    @Test
    void missingExecutionStillSendsAnEmptyArgumentObject() {
        FakeMcpTransport transport = new FakeMcpTransport();
        McpResilientConnection connection = connectionOver(transport);
        try {
            ToolExecuteResult result = new McpToolInvoker(connection, "search", json).execute(null);

            assertTrue(result.isSuccess(), result.getToolOutput());
            assertTrue(argumentsOf(transport).isEmpty());
        } finally {
            connection.close();
        }
    }

    private McpToolInvoker invokerOf() {
        return new McpToolInvoker(connectionOver(new FakeMcpTransport()), "search", json);
    }

    private McpResilientConnection connectionOver(McpClientTransport transport) {
        AtomicInteger closes = new AtomicInteger();
        McpSyncClient client = McpSessionFactory.connect(transport, McpClientSettings.defaults(), server());
        return McpTestSupport.connectionOf(() -> new McpTestSupport.CountingHandle(client, closes));
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> argumentsOf(FakeMcpTransport transport) {
        Map<String, Object> call = transport.receivedToolCalls().getFirst();
        return (Map<String, Object>) call.get("arguments");
    }

    private static McpConfig.MCP server() {
        return new McpConfig.MCP("fake", "fake server", McpTransport.STREAMABLE_HTTP,
                new McpConfig.StreamableHttp("https://fake.example/mcp", Map.of(),
                        Duration.ofSeconds(3), Duration.ofMillis(1_500)), 1_000);
    }

    private static McpSchema.Content text(String value) {
        return McpSchema.TextContent.builder(value).build();
    }

    /**
     * Delegates every method to the in-memory transport, except that a {@code tools/call} is rejected
     * the way a real server does: the request fails instead of being answered.
     *
     * <p>Composition rather than a switch on {@link FakeMcpTransport}: that fake belongs to the
     * discovery and health-check tests, and a rejecting server is a different scenario with a single
     * use here.</p>
     */
    private static final class RejectingTransport implements McpClientTransport {

        private final FakeMcpTransport delegate = new FakeMcpTransport();

        @Override
        public Mono<Void> connect(
                Function<Mono<McpSchema.JSONRPCMessage>, Mono<McpSchema.JSONRPCMessage>> inboundHandler) {
            return delegate.connect(inboundHandler);
        }

        @Override
        public Mono<Void> sendMessage(McpSchema.JSONRPCMessage message) {
            if (message instanceof McpSchema.JSONRPCRequest request
                    && McpSchema.METHOD_TOOLS_CALL.equals(request.method())) {
                throw new IllegalStateException("tools/call rejected by the fake server");
            }
            return delegate.sendMessage(message);
        }

        @Override
        public Mono<Void> closeGracefully() {
            return delegate.closeGracefully();
        }

        @Override
        public <T> T unmarshalFrom(Object data, TypeRef<T> typeRef) {
            return delegate.unmarshalFrom(data, typeRef);
        }
    }
}
