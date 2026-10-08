package com.summit.harness.springbootautoconfigure.config.mcp;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.summit.adapter.langchain4j.mcp.McpClientFactory;
import com.summit.adapter.langchain4j.mcp.AgentScopeMcpProvider;
import com.summit.core.conf.McpConfig;
import com.summit.core.conf.McpTransport;
import com.summit.core.mcp.ScopeMcpProvider;
 
import com.summit.core.mcp.McpToolScope;
import com.summit.core.tool.*;
import com.sun.net.httpserver.HttpServer;
import dev.langchain4j.mcp.client.McpClient;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.core.env.MapPropertySource;

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
    void enabledConfigurationRegistersAdapterProviderWithoutOpeningConnections() {
        try (AnnotationConfigApplicationContext context = context(Map.of("lingxi.mcp.enabled", true))) {
            context.registerBean(McpClientFactory.class, () -> new McpClientFactory() {
                @Override
                public McpClient create(String key, McpConfig.MCP server) {
                    throw new AssertionError("Auto-configuration must not open request connections");
                }
            });
            context.refresh();
            assertInstanceOf(AgentScopeMcpProvider.class, context.getBean(ScopeMcpProvider.class));
            assertEquals(1, context.getBeansOfType(ScopeMcpProvider.class).size());
            assertTrue(context.getBean(ToolRegistry.class).getTools().isEmpty());
        }
    }

    @Test
    void applicationProviderReplacesDefault() {
        try (AnnotationConfigApplicationContext context = context(Map.of("lingxi.mcp.enabled", true))) {
            ScopeMcpProvider custom = configuration -> McpToolScope.EMPTY;
            context.registerBean("customProvider", ScopeMcpProvider.class, () -> custom);
            context.refresh();
            assertSame(custom, context.getBean(ScopeMcpProvider.class));
            assertEquals(1, context.getBeansOfType(ScopeMcpProvider.class).size());
        }
    }

    @Test
    void applicationFactoryIsUsedByDefaultProvider() {
        AtomicInteger created = new AtomicInteger();
        McpClientFactory custom = new McpClientFactory() {
            @Override
            public McpClient create(String key, McpConfig.MCP server) {
                created.incrementAndGet();
                throw new IllegalStateException("test connection failure");
            }
        };
        try (AnnotationConfigApplicationContext context = context(Map.of("lingxi.mcp.enabled", true))) {
            context.registerBean("customFactory", McpClientFactory.class, () -> custom);
            context.refresh();
            assertSame(custom, context.getBean(McpClientFactory.class));
            assertEquals(0, created.get());
            assertTrue(context.getBean(ScopeMcpProvider.class).openScope(config(server("test"))).isEmpty());
            assertEquals(1, created.get());
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
                McpConfig.MCP github = new McpConfig.MCP("github", null, McpTransport.STREAMABLE_HTTP,
                        new McpConfig.StreamableHttp(url, Map.of("Authorization", "Bearer test-token"),
                                Duration.ofSeconds(3), Duration.ofSeconds(4)),
                        321);

                McpToolScope scope = context.getBean(ScopeMcpProvider.class)
                        .openScope(config(github));
                ToolDefinition<?> tool = scope.getTool("mcp_get_file_contents");
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
                assertNull(scope.getTool("mcp_get_file_contents"));
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
        return new McpConfig.MCP(name, null, McpTransport.STREAMABLE_HTTP,
                new McpConfig.StreamableHttp("https://" + name + ".example/mcp", Map.of(),
                        Duration.ofSeconds(3), Duration.ofSeconds(4)),
                100);
    }

    private static AnnotationConfigApplicationContext context(Map<String, Object> properties) {
        var context = new AnnotationConfigApplicationContext();
        context.getEnvironment().getPropertySources().addFirst(new MapPropertySource("test", properties));
        context.registerBean(ToolRegistry.class, () -> new ToolRegistry(List.of()));
        context.register(McpAutoConfiguration.class);
        return context;
    }

}
