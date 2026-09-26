package com.summit.harness.springbootautoconfigure.conf.mcp;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.summit.adapter.langchain4j.mcp.McpClientFactory;
import com.summit.core.mcp.McpProvider;
import com.summit.core.tool.*;
import com.summit.harness.springbootautoconfigure.properties.McpProperties;
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
    void registersCustomProvidersAndIsolatesDiscoveryFailure() {
        ToolDefinition<?> tool = tool("external_search");
        try (var context = context(Map.of("lingxi.mcp.enabled", true))) {
            context.registerBean("brokenProvider", McpProvider.class, () -> () -> {
                throw new IllegalStateException("unavailable");
            });
            context.registerBean("customProvider", McpProvider.class, () -> () -> List.of(tool));
            context.refresh();
            assertSame(tool, context.getBean(ToolRegistry.class).getTool("external_search"));
        }
    }

    @Test
    void connectionFailureDoesNotHideHealthyServerAndOwnedClientClosesOnce() {
        AtomicInteger closed = new AtomicInteger();
        var context = context(Map.of(
                "lingxi.mcp.enabled", true,
                "lingxi.mcp.servers.broken.url", "https://broken.example/mcp",
                "lingxi.mcp.servers.healthy.url", "https://healthy.example/mcp",
                "lingxi.mcp.servers.disabled.enabled", false));
        context.registerBean(McpClientFactory.class, () -> new McpClientFactory() {
            @Override
            public McpClient create(String key, String url, Map<String, String> headers,
                                    Duration initializationTimeout, Duration executionTimeout) {
                if (key.equals("broken")) throw new IllegalStateException("connection failed");
                assertEquals("healthy", key);
                return fakeClient(closed);
            }
        });
        ConfiguredMcpProvider provider;
        try (context) {
            context.refresh();
            provider = context.getBean(ConfiguredMcpProvider.class);
            assertNotNull(context.getBean(ToolRegistry.class).getTool("healthy_search"));
            assertEquals(1, context.getBean(ToolRegistry.class).getTools().size());
        }
        assertEquals(1, closed.get());
        provider.close();
        assertEquals(1, closed.get());
    }

    @Test
    void nameCollisionFailsBeforeOverwritingAnyLocalTool() {
        ToolDefinition<?> local = tool("github_search");
        ToolRegistry registry = new ToolRegistry(List.of(local));
        McpToolRegistrar registrar = new McpToolRegistrar(registry,
                List.of(() -> List.of(tool("github_other"), tool("github_search"))));
        assertThrows(IllegalStateException.class, registrar::afterSingletonsInstantiated);
        assertSame(local, registry.getTool("github_search"));
        assertNull(registry.getTool("github_other"));
    }

    @Test
    void rejectsInvalidConfigurationBeforeConnecting() {
        McpProperties properties = new McpProperties();
        McpProperties.Server server = new McpProperties.Server();
        server.setUrl("file:///tmp/mcp");
        properties.getServers().put("github", server);
        assertThrows(IllegalArgumentException.class,
                () -> new ConfiguredMcpProvider(properties, new McpClientFactory()));
        server.setUrl("https://example.com/mcp");
        server.setExecutionTimeout(Duration.ZERO);
        assertThrows(IllegalArgumentException.class,
                () -> new ConfiguredMcpProvider(properties, new McpClientFactory()));
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
            ConfiguredMcpProvider provider;
            try (var context = context(Map.of(
                    "lingxi.mcp.enabled", true,
                    "lingxi.mcp.servers.github.url", "http://127.0.0.1:" + server.getAddress().getPort() + "/mcp",
                    "lingxi.mcp.servers.github.headers.Authorization", "Bearer test-token",
                    "lingxi.mcp.servers.github.initialization-timeout", "3s",
                    "lingxi.mcp.servers.github.execution-timeout", "4s",
                    "lingxi.mcp.servers.github.max-output", 321))) {
                context.refresh();
                provider = context.getBean(ConfiguredMcpProvider.class);
                ToolRegistry registry = context.getBean(ToolRegistry.class);
                ToolDefinition<?> tool = registry.getTool("github_get_file_contents");
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
            }
            // This SDK closes the HTTP transport without sending a session DELETE request.
            assertThrows(IllegalStateException.class, provider::provide);
        } finally {
            server.stop(0);
        }
    }

    private static AnnotationConfigApplicationContext context(Map<String, Object> properties) {
        var context = new AnnotationConfigApplicationContext();
        context.getEnvironment().getPropertySources().addFirst(new MapPropertySource("test", properties));
        context.registerBean(ToolRegistry.class, () -> new ToolRegistry(List.of()));
        context.register(McpAutoConfiguration.class);
        return context;
    }

    private static ToolDefinition<?> tool(String name) {
        return ToolDefinition.builder().id(name).name(name).timeout(5L).maxOutput(100)
                .executor(execution -> ToolExecuteResult.success("ok")).build();
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
