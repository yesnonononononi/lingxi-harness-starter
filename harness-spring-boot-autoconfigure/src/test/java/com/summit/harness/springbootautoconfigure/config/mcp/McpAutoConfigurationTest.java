package com.summit.harness.springbootautoconfigure.config.mcp;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.summit.adapter.langchain4j.mcp.McpClientFactory;
import com.summit.core.conf.McpConfig;
import com.summit.core.conf.McpTransport;
import com.summit.core.mcp.McpProvider;
import com.summit.core.mcp.McpRegister;
import com.summit.core.mcp.McpToolScope;
import com.summit.core.tool.*;
import com.sun.net.httpserver.HttpServer;
import dev.langchain4j.agent.tool.ToolSpecification;
import dev.langchain4j.mcp.client.McpClient;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.core.env.MapPropertySource;

import java.lang.reflect.Proxy;
import java.net.InetSocketAddress;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;

class McpAutoConfigurationTest {
    @Test
    void disabledByDefaultDoesNotCreateConnections() {
        try (var context = context(Map.of("lingxi.mcp.servers.github.url", "invalid"))) {
            context.refresh();
            assertTrue(context.getBeansOfType(McpClientFactory.class).isEmpty());
            assertTrue(context.getBean(ToolRegistry.class).getTools().isEmpty());
        }
    }

    @Test
    void connectionFailureDoesNotHideHealthyServerAndOwnedClientClosesOnce() {
        AtomicInteger closed = new AtomicInteger();
        var context = context(Map.of("lingxi.mcp.enabled", true));
        context.registerBean(McpClientFactory.class, () -> new McpClientFactory() {
            @Override
            public McpClient create(String key, McpConfig.MCP server) {
                if (key.equals("broken")) throw new IllegalStateException("connection failed");
                assertEquals("healthy", key);
                return fakeClient(closed);
            }
        });
        try (context) {
            context.refresh();
            McpRegister register = context.getBean(McpRegister.class);
            McpToolScope scope = register.open(config(server("broken"), server("healthy")));

            assertNotNull(scope.getTool("mcp_healthy_search"));
            assertEquals(1, scope.getTools().size());
            assertEquals(0, closed.get(), "the healthy connection stays open for the request");

            scope.close();
            assertEquals(1, closed.get());
        }
    }

    /** A repeated request must not collide, and the registry must stay free of remote tools. */
    @Test
    void eachRequestGetsItsOwnScopeAndNeverTouchesTheRegistry() {
        AtomicInteger created = new AtomicInteger();
        AtomicInteger closed = new AtomicInteger();
        var context = context(Map.of("lingxi.mcp.enabled", true));
        context.registerBean(McpClientFactory.class, () -> new McpClientFactory() {
            @Override
            public McpClient create(String key, McpConfig.MCP server) {
                created.incrementAndGet();
                return fakeClient(closed);
            }
        });
        try (context) {
            context.refresh();
            McpRegister register = context.getBean(McpRegister.class);
            McpConfig config = config(server("healthy"));

            McpToolScope first = register.open(config);
            McpToolScope second = register.open(config);

            assertNotSame(first, second);
            assertEquals(2, created.get());
            assertNotNull(first.getTool("mcp_healthy_search"));
            assertNotNull(second.getTool("mcp_healthy_search"));
            assertTrue(context.getBean(ToolRegistry.class).getTools().isEmpty(),
                    "MCP tools must never enter the process-wide registry");

            first.close();
            assertEquals(1, closed.get());
            second.close();
            assertEquals(2, closed.get());
        }
    }

    /** A request without MCP configuration opens no connection at all. */
    @Test
    void emptyConfigurationOpensNoConnection() {
        AtomicInteger created = new AtomicInteger();
        var context = context(Map.of("lingxi.mcp.enabled", true));
        context.registerBean(McpClientFactory.class, () -> new McpClientFactory() {
            @Override
            public McpClient create(String key, McpConfig.MCP server) {
                created.incrementAndGet();
                return fakeClient(new AtomicInteger());
            }
        });
        try (context) {
            context.refresh();
            McpToolScope scope = context.getBean(McpProvider.class).openScope(new McpConfig());
            assertTrue(scope.isEmpty());
            assertEquals(0, created.get());
            assertSame(McpToolScope.EMPTY, scope);
        }
    }

    /** The same name declared twice stays confined to its own scope instead of throwing. */
    @Test
    void duplicateToolNamesAcrossRequestsDoNotCollide() {
        var context = context(Map.of("lingxi.mcp.enabled", true));
        context.registerBean(McpClientFactory.class, () -> new McpClientFactory() {
            @Override
            public McpClient create(String key, McpConfig.MCP server) {
                return fakeClient(new AtomicInteger());
            }
        });
        try (context) {
            context.refresh();
            McpRegister register = context.getBean(McpRegister.class);
            try (McpToolScope ignored = register.open(config(server("healthy")))) {
                assertDoesNotThrow(() -> register.open(config(server("healthy"))).close());
            }
        }
    }

    @Test
    @Timeout(30)
    void connectsDiscoversRegistersAndCallsThroughRealHttpTransport() throws Exception {
        ObjectMapper mapper = new ObjectMapper();
        AtomicReference<JsonNode> call = new AtomicReference<>();
        List<String> authorization = new CopyOnWriteArrayList<>();
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/mcp", exchange -> {
            try (exchange) {
                if (exchange.getRequestMethod().equals("DELETE")) {
                    exchange.sendResponseHeaders(204, -1);
                    return;
                }
                if (!exchange.getRequestMethod().equals("POST")) {
                    exchange.sendResponseHeaders(405, -1);
                    return;
                }
                authorization.add(exchange.getRequestHeaders().getFirst("Authorization"));
                JsonNode request = mapper.readTree(exchange.getRequestBody());
                if (!request.has("id")) {
                    exchange.sendResponseHeaders(202, -1);
                    return;
                }
                Object result = switch (request.path("method").asText()) {
                    case "initialize" -> Map.of(
                            "protocolVersion", request.path("params").path("protocolVersion").asText(),
                            "capabilities", Map.of("tools", Map.of()),
                            "serverInfo", Map.of("name", "test-server", "version", "1.0"));
                    case "tools/list" -> Map.of("tools", List.of(Map.of(
                            "name", "get_file_contents", "description", "Read a file",
                            "inputSchema", Map.of("type", "object", "properties", Map.of(
                                    "path", Map.of("type", "string")), "required", List.of("path")))));
                    case "tools/call" -> {
                        call.set(request.path("params"));
                        yield Map.of("content", List.of(Map.of("type", "text", "text", "file contents")),
                                "isError", false);
                    }
                    default -> Map.of();
                };
                byte[] body = mapper.writeValueAsBytes(Map.of("jsonrpc", "2.0",
                        "id", request.get("id"), "result", result));
                exchange.getResponseHeaders().set("Content-Type", "application/json");
                exchange.getResponseHeaders().set("Mcp-Session-Id", "test-session");
                exchange.sendResponseHeaders(200, body.length);
                exchange.getResponseBody().write(body);
            }
        });
        server.start();
        try {
            try (var context = context(Map.of("lingxi.mcp.enabled", true))) {
                context.refresh();
                String url = "http://127.0.0.1:" + server.getAddress().getPort() + "/mcp";
                McpConfig.MCP github = new McpConfig.MCP("github", McpTransport.STREAMABLE_HTTP,
                        new McpConfig.StreamableHttp(url, Map.of("Authorization", "Bearer test-token"),
                                Duration.ofSeconds(3), Duration.ofSeconds(4)),
                        null, 321);

                McpToolScope scope = context.getBean(McpProvider.class)
                        .openScope(config(github));
                ToolDefinition<?> tool = scope.getTool("mcp_github_get_file_contents");
                assertNotNull(tool);
                assertEquals(9L, tool.timeout());
                assertEquals(321, tool.maxOutput());
                assertTrue(tool.parametersJsonSchema().contains("path"));
                ToolExecuteResult result = tool.executor().execute(ToolExecution.builder()
                        .id("call-1").toolDefinition(tool).args("{\"path\":\"README.md\"}").build());
                assertTrue(result.isSuccess(), result.getToolOutput());
                assertEquals("file contents", result.getToolOutput());
                assertEquals("get_file_contents", call.get().path("name").asText());
                assertEquals("README.md", call.get().path("arguments").path("path").asText());
                assertFalse(authorization.isEmpty());
                assertTrue(authorization.stream().allMatch("Bearer test-token"::equals));

                scope.close();
                assertNull(scope.getTool("mcp_github_get_file_contents"));
            }
            // This SDK closes the HTTP transport without sending a session DELETE request.

        } finally {
            server.stop(0);
        }
    }

    private static McpConfig config(McpConfig.MCP... servers) {
        McpConfig config = new McpConfig();
        config.setMcp(List.of(servers));
        return config;
    }

    private static McpConfig.MCP server(String name) {
        return new McpConfig.MCP(name, McpTransport.STREAMABLE_HTTP,
                new McpConfig.StreamableHttp("https://" + name + ".example/mcp", Map.of(),
                        Duration.ofSeconds(3), Duration.ofSeconds(4)),
                null, 100);
    }

    private static AnnotationConfigApplicationContext context(Map<String, Object> properties) {
        var context = new AnnotationConfigApplicationContext();
        context.getEnvironment().getPropertySources().addFirst(new MapPropertySource("test", properties));
        context.registerBean(ToolRegistry.class, () -> new ToolRegistry(List.of()));
        context.register(McpAutoConfiguration.class);
        return context;
    }

    private static McpClient fakeClient(AtomicInteger closed) {
        return (McpClient) Proxy.newProxyInstance(McpClient.class.getClassLoader(), new Class<?>[]{McpClient.class},
                (proxy, method, arguments) -> switch (method.getName()) {
                    case "key" -> "healthy";
                    case "listTools" -> List.of(ToolSpecification.builder().name("search").build());
                    case "close" -> { closed.incrementAndGet(); yield null; }
                    default -> throw new UnsupportedOperationException(method.getName());
                });
    }
}
