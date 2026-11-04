package mcp;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.summit.core.conf.McpConfig;
import com.summit.core.conf.McpTransport;
import com.summit.core.mcp.McpSession;
import com.summit.core.mcp.McpSessionType;
import com.summit.core.tool.ToolDefinition;
import com.summit.core.tool.ToolExecuteResult;
import com.summit.core.tool.ToolExecution;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Real HTTP handshake against an embedded server.
 *
 * <p>Three things can only be observed here and not in a unit test: the request path the server
 * actually receives, the headers that travel with it, and the absence of the subscription calls the
 * retired client used to send (which most servers answer with 404 and a stack trace).</p>
 *
 * <p>The path is deliberately not {@code /mcp}: the SDK resolves a relative endpoint against its base
 * URI, and an absolute-path endpoint silently discards the base path, so a root path would make the
 * regression invisible.</p>
 */
@Timeout(90)
class McpHttpEndToEndTest {

    private static final String ENDPOINT_PATH = "/api/v1/mcp";
    private static final String TOKEN = "Bearer test-token";

    private final ObjectMapper json = new ObjectMapper();
    private final List<String> requestPaths = new CopyOnWriteArrayList<>();
    private final List<String> requestMethods = new CopyOnWriteArrayList<>();
    private final List<String> mcpMethods = new CopyOnWriteArrayList<>();
    private final List<String> authorizations = new CopyOnWriteArrayList<>();
    private final AtomicInteger toolListCalls = new AtomicInteger();
    private final AtomicInteger initializeDelayMillis = new AtomicInteger();

    private HttpServer server;
    private McpSessionFactory factory;
    private String url;

    @BeforeEach
    void startServer() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext(ENDPOINT_PATH, this::handle);
        server.start();
        url = "http://127.0.0.1:" + server.getAddress().getPort() + ENDPOINT_PATH;
        factory = new McpSessionFactory(McpClientSettings.defaults(),
                List.of(new mcp.transport.McpHttpTransportBuilder(json)), null, json);
    }

    @AfterEach
    void stopServer() {
        factory.close();
        server.stop(0);
    }

    @Test
    void initializesListsCallsAndPingsOverRealHttp() {
        McpConfig.MCP server = httpServer(3);

        try (McpSession session = factory.openSession(server, null, McpSessionType.PER_REQUEST, "mcp_")) {
            System.out.println("MCP HTTP request paths received by the fixture: " + requestPaths);
            assertTrue(requestPaths.stream().allMatch(ENDPOINT_PATH::equals),
                    "the configured path must reach the server verbatim, got " + requestPaths);

            // Two pages: the client has to follow the cursor to publish both tools.
            assertEquals(List.of("alpha", "beta"), session.serverToolNames());
            assertEquals(List.of("mcp_alpha", "mcp_beta"),
                    session.tools().stream().map(ToolDefinition::name).toList());
            assertEquals(2, toolListCalls.get(), "one page per tools/list round");

            assertFalse(authorizations.isEmpty(), "the configured header must travel with the call");
            assertTrue(authorizations.stream().allMatch(TOKEN::equals), "got " + authorizations);

            ToolDefinition<?> alpha = session.tools().getFirst();
            ToolExecuteResult ok = alpha.executor().execute(ToolExecution.builder()
                    .id("call-1").toolDefinition(alpha).args("{\"want\":\"text\"}").build());
            assertTrue(ok.isSuccess(), ok.getToolOutput());
            assertEquals("alpha text", ok.getToolOutput());

            ToolDefinition<?> beta = session.tools().get(1);
            ToolExecuteResult error = beta.executor().execute(ToolExecution.builder()
                    .id("call-2").toolDefinition(beta).args("{\"want\":\"error\"}").build());
            assertFalse(error.isSuccess());
            assertEquals("beta failed", error.getToolOutput());

            ToolExecuteResult structured = beta.executor().execute(ToolExecution.builder()
                    .id("call-3").toolDefinition(beta).args("{\"want\":\"structured\"}").build());
            assertTrue(structured.isSuccess());
            assertEquals("{\"value\":7}", structured.getToolOutput());

            session.checkHealth();
            assertTrue(mcpMethods.contains("ping"), "the health check must reach the server");
        }

        System.out.println("MCP HTTP methods sent by the client: " + mcpMethods);
        System.out.println("MCP HTTP request methods received by the fixture: " + requestMethods);
        assertTrue(mcpMethods.stream().noneMatch(name -> name.contains("subscribe") || name.contains("listen")),
                "no change subscription may be requested, got " + mcpMethods);
        // The streamable-http transport may open its optional server-to-client stream, which is a GET
        // the fixture answers with 405; that is the protocol, not the retired client's SSE channel.
        // What must never appear is a subscription: asserted above, where the method names are.
    }

    /** A pinned protocol version is enforced through the negotiation list. */
    @Test
    void apinnedProtocolVersionIsNegotiated() {
        McpConfig.MCP server = new McpConfig.MCP("pinned", null, McpTransport.STREAMABLE_HTTP,
                new McpConfig.StreamableHttp(url, Map.of("Authorization", TOKEN),
                        Duration.ofSeconds(5), Duration.ofSeconds(5), "2025-06-18", null, null, null), 1_000);

        try (McpSession session = factory.openSession(server)) {
            assertNotNull(session);
            assertEquals("2025-06-18", ((McpSyncSession) session).negotiatedProtocolVersion());
        }
    }

    /**
     * The initialization timeout bounds the handshake, and it is the initialization timeout — not the
     * request timeout — that fires: a server slower than the configured budget must fail the open.
     */
    @Test
    void theInitializationTimeoutBoundsTheHandshake() {
        initializeDelayMillis.set(400);
        McpConfig.MCP slow = new McpConfig.MCP("slow", null, McpTransport.STREAMABLE_HTTP,
                new McpConfig.StreamableHttp(url, Map.of(), Duration.ofMillis(80), Duration.ofSeconds(5)), 1_000);

        RuntimeException failure = assertThrows(RuntimeException.class, () -> factory.openSession(slow));

        assertTrue(messageChain(failure).contains("initialize"),
                "expected an initialization failure, got " + messageChain(failure));
    }

    private static String messageChain(Throwable failure) {
        StringBuilder chain = new StringBuilder();
        for (Throwable current = failure; current != null; current = current.getCause()) {
            chain.append(current.getMessage()).append(" | ");
        }
        return chain.toString();
    }

    private McpConfig.MCP httpServer(int initializationsAllowed) {
        return new McpConfig.MCP("github", null, McpTransport.STREAMABLE_HTTP,
                new McpConfig.StreamableHttp(url, Map.of("Authorization", TOKEN),
                        Duration.ofSeconds(3), Duration.ofSeconds(3)), 321);
    }

    /** Answers like a streamable-http MCP server, without a session id and without SSE support. */
    private void handle(HttpExchange exchange) throws IOException {
        try (exchange) {
            requestPaths.add(exchange.getRequestURI().getRawPath());
            requestMethods.add(exchange.getRequestMethod());
            if (!"POST".equals(exchange.getRequestMethod())) {
                // 405 is how a server says "I offer no out-of-band stream"; the client accepts it.
                exchange.sendResponseHeaders(405, -1);
                return;
            }
            String authorization = exchange.getRequestHeaders().getFirst("Authorization");
            if (authorization != null) {
                authorizations.add(authorization);
            }
            JsonNode request = json.readTree(exchange.getRequestBody());
            String method = request.path("method").asText("");
            if (!method.isEmpty()) {
                mcpMethods.add(method);
            }
            if (!request.has("id")) {
                exchange.sendResponseHeaders(202, -1);
                return;
            }
            if (method.equals("initialize") && initializeDelayMillis.get() > 0) {
                sleep(initializeDelayMillis.get());
            }
            Object result = switch (method) {
                case "initialize" -> Map.of(
                        "protocolVersion", request.path("params").path("protocolVersion").asText("2025-11-25"),
                        "capabilities", Map.of("tools", Map.of()),
                        "serverInfo", Map.of("name", "http-fixture", "version", "1.0"),
                        "instructions", "fixture instructions");
                case "ping" -> Map.of();
                case "tools/list" -> toolPage(request.path("params").path("cursor").asText(""));
                case "tools/call" -> toolCall(request.path("params"));
                default -> Map.of();
            };
            byte[] body = json.writeValueAsBytes(Map.of("jsonrpc", "2.0", "id", request.get("id"), "result", result));
            exchange.getResponseHeaders().set("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, body.length);
            exchange.getResponseBody().write(body);
        }
    }

    private Map<String, Object> toolPage(String cursor) {
        int page = toolListCalls.incrementAndGet();
        if (cursor.isEmpty() || page == 1) {
            List<Map<String, Object>> tools = new ArrayList<>();
            tools.add(Map.of("name", "alpha", "description", "First tool",
                    "inputSchema", Map.of("type", "object", "properties", Map.of("want", Map.of("type", "string")))));
            return Map.of("tools", tools, "nextCursor", "page-2");
        }
        return Map.of("tools", List.of(Map.of("name", "beta", "description", "Second tool",
                "inputSchema", Map.of("type", "object", "properties", Map.of("want", Map.of("type", "string"))))));
    }

    private Map<String, Object> toolCall(JsonNode params) {
        String name = params.path("name").asText();
        String want = params.path("arguments").path("want").asText();
        if (want.equals("error")) {
            return Map.of("content", List.of(Map.of("type", "text", "text", name + " failed")), "isError", true);
        }
        if (want.equals("structured")) {
            return Map.of("content", List.of(), "structuredContent", Map.of("value", 7));
        }
        return Map.of("content", List.of(Map.of("type", "text", "text", name + " text")), "isError", false);
    }

    private static void sleep(long millis) {
        try {
            Thread.sleep(millis);
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
        }
    }
}
