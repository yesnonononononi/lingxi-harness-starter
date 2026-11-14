package mcp;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.summit.core.conf.McpConfig;
import com.summit.core.conf.McpTransport;
import com.summit.core.mcp.McpSession;
import com.summit.core.mcp.McpSessionType;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import io.modelcontextprotocol.client.transport.McpHttpClientTransportAuthorizationException;
import mcp.transport.McpHttpTransportBuilder;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The authorization hooks of the client library, wired the way the transport builder wires them.
 *
 * <p>A real exchange is what proves the wiring: the credential supplied per request replaces the
 * configured {@code Authorization} header instead of being appended to it, a server the provider
 * declines keeps its configuration untouched, and a rejection reaches the caller as the library's
 * authorization exception — the one signal that separates "needs authorization" from "cannot be
 * reached". The last test pins the recovery path the design relies on: the rejection is not
 * repaired in place, a rebuilt connection carries whatever credential is current at that moment.</p>
 */
@Timeout(60)
class McpAuthorizationWiringTest {

    private static final String ENDPOINT_PATH = "/mcp";
    private static final String PLACEHOLDER = "Bearer placeholder";
    private static final String RUNTIME_TOKEN = "Bearer runtime-token";

    private final ObjectMapper json = new ObjectMapper();
    private final List<String> authorizations = new CopyOnWriteArrayList<>();
    private final AtomicInteger rejections = new AtomicInteger();
    private final AtomicReference<String> acceptedToken = new AtomicReference<>(RUNTIME_TOKEN);

    private HttpServer server;
    private McpSessionFactory factory;
    private String url;

    @BeforeEach
    void startServer() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext(ENDPOINT_PATH, this::handle);
        server.start();
        url = "http://127.0.0.1:" + server.getAddress().getPort() + ENDPOINT_PATH;
    }

    @AfterEach
    void stopServer() {
        closeFactory();
        server.stop(0);
    }

    @Test
    void providedCredentialReplacesTheConfiguredHeader() {
        StubAuthorization authorization = new StubAuthorization(RUNTIME_TOKEN);
        factory = factoryWith(authorization);

        try (McpSession session = factory.openSession(serverWithStaticHeader(), null, McpSessionType.PER_REQUEST, "")) {
            assertEquals(List.of("alpha"), session.serverToolNames());
        }

        assertFalse(authorizations.isEmpty(), "the credential must travel with the requests");
        assertEquals(List.of(RUNTIME_TOKEN), authorizations.stream().distinct().toList(),
                "the configured header must be replaced, not added to");
        assertEquals(0, rejections.get());
        assertEquals(0, authorization.reported.get());
    }

    @Test
    void declinedServerKeepsItsConfiguredRequest() {
        acceptedToken.set(PLACEHOLDER);
        factory = factoryWith(null);

        try (McpSession session = factory.openSession(serverWithStaticHeader(), null, McpSessionType.PER_REQUEST, "")) {
            assertEquals(List.of("alpha"), session.serverToolNames());
        }

        assertEquals(List.of(PLACEHOLDER), authorizations.stream().distinct().toList());
        assertEquals(0, rejections.get());
    }

    @Test
    void authorizationErrorIsReportedAndSurfacesToTheCaller() {
        StubAuthorization authorization = new StubAuthorization(PLACEHOLDER);
        factory = factoryWith(authorization);

        RuntimeException failure = assertThrows(RuntimeException.class,
                () -> factory.openSession(serverWithStaticHeader()));

        assertTrue(hasAuthorizationFailure(failure),
                "expected the authorization exception in the cause chain, got " + describeChain(failure));
        assertEquals(1, authorization.reported.get(), "the rejection must reach the provider");
    }

    /**
     * Recovery goes through the next connection: it copies the request template again, so a
     * credential renewed after the rejection is what actually reaches the server.
     */
    @Test
    void credentialIsReadAgainWhenTheConnectionIsRebuilt() {
        StubAuthorization authorization = new StubAuthorization(PLACEHOLDER);
        factory = factoryWith(authorization);

        RuntimeException rejected = assertThrows(RuntimeException.class,
                () -> factory.openSession(serverWithStaticHeader()));
        assertTrue(hasAuthorizationFailure(rejected), describeChain(rejected));

        closeFactory();
        authorization.token.set(RUNTIME_TOKEN);
        factory = factoryWith(authorization);

        try (McpSession session = factory.openSession(serverWithStaticHeader(), null, McpSessionType.PER_REQUEST, "")) {
            assertEquals(List.of("alpha"), session.serverToolNames());
        }
        assertTrue(authorizations.contains(RUNTIME_TOKEN), "the rebuilt connection must use the new credential");
    }

    private void closeFactory() {
        if (factory != null) {
            factory.close();
            factory = null;
        }
    }

    private static boolean hasAuthorizationFailure(Throwable failure) {
        for (Throwable current = failure; current != null; current = current.getCause()) {
            if (current instanceof McpHttpClientTransportAuthorizationException) {
                return true;
            }
            if (current.getCause() == current) {
                break;
            }
        }
        return false;
    }

    private static String describeChain(Throwable failure) {
        StringBuilder chain = new StringBuilder();
        for (Throwable current = failure; current != null; current = current.getCause()) {
            chain.append(current.getClass().getSimpleName()).append(": ").append(current.getMessage()).append(" | ");
            if (current.getCause() == current) {
                break;
            }
        }
        return chain.toString();
    }

    /** @param authorization the handling to publish, or null to build transports without one */
    private McpSessionFactory factoryWith(StubAuthorization authorization) {
        McpAuthorizationProvider provider = authorization == null ? null : server -> authorization;
        return new McpSessionFactory(McpClientSettings.defaults(),
                List.of(new McpHttpTransportBuilder(json, provider)), null, json);
    }

    private McpConfig.MCP serverWithStaticHeader() {
        return new McpConfig.MCP("fixture", null, McpTransport.STREAMABLE_HTTP,
                new McpConfig.StreamableHttp(url, Map.of("Authorization", PLACEHOLDER),
                        Duration.ofSeconds(5), Duration.ofSeconds(5)), 4_000);
    }

    /** Answers like a streamable-http server; a message without the accepted credential gets 401. */
    private void handle(HttpExchange exchange) throws IOException {
        try (exchange) {
            if (!"POST".equals(exchange.getRequestMethod())) {
                // 405 is how a server says "I offer no out-of-band stream"; the client accepts it.
                exchange.sendResponseHeaders(405, -1);
                return;
            }
            String authorization = exchange.getRequestHeaders().getFirst("Authorization");
            if (authorization != null) {
                authorizations.add(authorization);
            }
            if (!acceptedToken.get().equals(authorization)) {
                rejections.incrementAndGet();
                exchange.getResponseHeaders().set("WWW-Authenticate",
                        "Bearer resource_metadata=\"http://127.0.0.1/.well-known/oauth-protected-resource\"");
                exchange.sendResponseHeaders(401, -1);
                return;
            }
            JsonNode request = json.readTree(exchange.getRequestBody());
            if (!request.has("id")) {
                exchange.sendResponseHeaders(202, -1);
                return;
            }
            Object result = switch (request.path("method").asText("")) {
                case "initialize" -> Map.of(
                        "protocolVersion", request.path("params").path("protocolVersion").asText("2025-11-25"),
                        "capabilities", Map.of("tools", Map.of()),
                        "serverInfo", Map.of("name", "auth-fixture", "version", "1.0"));
                case "tools/list" -> Map.of("tools", List.of(Map.of(
                        "name", "alpha", "description", "First tool",
                        "inputSchema", Map.of("type", "object", "properties", Map.of()))));
                default -> Map.of();
            };
            byte[] body = json.writeValueAsBytes(Map.of("jsonrpc", "2.0", "id", request.get("id"), "result", result));
            exchange.getResponseHeaders().set("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, body.length);
            exchange.getResponseBody().write(body);
        }
    }

    /** Supplies the credential of the moment per request and counts what the transport reported. */
    private static final class StubAuthorization implements McpServerAuthorization {

        private final AtomicReference<String> token;
        private final AtomicInteger reported = new AtomicInteger();

        StubAuthorization(String token) {
            this.token = new AtomicReference<>(token);
        }

        @Override
        public void customize(HttpRequest.Builder requestBuilder) {
            requestBuilder.setHeader("Authorization", token.get());
        }

        @Override
        public void onAuthorizationError(HttpResponse.ResponseInfo response) {
            reported.incrementAndGet();
        }
    }
}
