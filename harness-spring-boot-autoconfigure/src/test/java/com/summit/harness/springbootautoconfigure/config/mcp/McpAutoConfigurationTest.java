package com.summit.harness.springbootautoconfigure.config.mcp;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.module.SimpleModule;
import com.fasterxml.jackson.databind.ser.std.ToStringSerializer;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import com.summit.core.conf.McpConfig;
import com.summit.core.conf.McpTransport;
import com.summit.core.mcp.McpSession;
import com.summit.core.mcp.McpToolScope;
import com.summit.core.mcp.ScopeMcpProvider;
import com.summit.core.runtime.workspace.Workspace;
import com.summit.core.tool.ToolDefinition;
import com.summit.core.tool.ToolExecuteResult;
import com.summit.core.tool.ToolExecution;
import com.summit.core.tool.ToolExecutor;
import com.summit.core.tool.ToolRegistry;
import com.summit.harness.springbootautoconfigure.config.json.JsonConfig;
import io.modelcontextprotocol.spec.McpClientTransport;
import mcp.AgentScopeMcpProvider;
import mcp.McpClientSettings;
import mcp.McpClientTransportBuilder;
import mcp.McpSessionFactory;
import mcp.transport.McpHttpTransportBuilder;
import mcp.transport.McpStdioTransportBuilder;
import org.junit.jupiter.api.Test;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.MapPropertySource;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ScheduledExecutorService;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** The MCP infrastructure must be wired without ever opening a connection at start-up. */
class McpAutoConfigurationTest {

    @Test
    void registersInfrastructureWithoutExposingGlobalTools() {
        try (AnnotationConfigApplicationContext context = context(Map.of())) {
            context.refresh();
            assertNotNull(context.getBean(McpClientSettings.class));
            assertNotNull(context.getBean(McpSessionFactory.class));
            assertInstanceOf(AgentScopeMcpProvider.class, context.getBean(ScopeMcpProvider.class));
            assertEquals(1, context.getBeansOfType(ScopeMcpProvider.class).size());
            assertTrue(context.getBean(RecordingToolRegistry.class).registrations.isEmpty());
            assertFalse(context.getBean(McpSessionFactory.class).isClosed());
        }
    }

    /** The transports are published as one replaceable list, HTTP first so it wins for HTTP entries. */
    @Test
    void defaultTransportsCoverHttpAndStdio() {
        try (AnnotationConfigApplicationContext context = context(Map.of())) {
            context.refresh();
            List<McpClientTransportBuilder> builders = context.getBean("mcpTransportBuilders", List.class);
            assertEquals(2, builders.size());
            assertInstanceOf(McpHttpTransportBuilder.class, builders.get(0));
            assertInstanceOf(McpStdioTransportBuilder.class, builders.get(1));
        }
    }

    @Test
    void mcpKeepsLargeIntegersNumericWithApplicationIdSerializer() throws IOException {
        ObjectMapper protocolJson = new ObjectMapper();
        SimpleModule idModule = new SimpleModule();
        idModule.addSerializer(Long.class, ToStringSerializer.instance);
        ObjectMapper applicationJson = new ObjectMapper().registerModule(idModule);
        long maximum = 9_007_199_254_740_991L;
        Map<String, Object> dimension = Map.of("type", "integer", "exclusiveMinimum", 0, "maximum", maximum);
        Map<String, Object> schema = Map.of("type", "object", "properties", Map.of("width", dimension, "height", dimension));
        assertTrue(applicationJson.valueToTree(schema).at("/properties/width/maximum").isTextual());

        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/mcp", exchange -> handleResizeFixture(exchange, protocolJson, schema));
        server.start();
        try (AnnotationConfigApplicationContext context = context(Map.of())) {
            context.registerBean(ObjectMapper.class, () -> applicationJson);
            context.refresh();
            String url = "http://127.0.0.1:" + server.getAddress().getPort() + "/mcp";
            Duration timeout = Duration.ofSeconds(3);
            McpConfig.MCP config = new McpConfig.MCP("chrome-devtools", null, McpTransport.STREAMABLE_HTTP,
                    new McpConfig.StreamableHttp(url, Map.of(), timeout, timeout, "2025-06-18", false, null, null), 1_000);
            try (McpSession session = context.getBean(McpSessionFactory.class).openSession(config)) {
                ToolDefinition<?> resize = session.tools().getFirst();
                assertEquals("resize_page", resize.name());
                JsonNode parameters = protocolJson.readTree(resize.parametersJsonSchema());
                JsonNode widthMaximum = parameters.at("/properties/width/maximum");
                JsonNode heightMaximum = parameters.at("/properties/height/maximum");
                assertTrue(widthMaximum.isIntegralNumber(), resize.parametersJsonSchema());
                assertTrue(heightMaximum.isIntegralNumber(), resize.parametersJsonSchema());
                assertEquals(maximum, widthMaximum.longValue());
                assertEquals(maximum, heightMaximum.longValue());

                ToolExecuteResult result = resize.executor().execute(ToolExecution.builder()
                        .id("resize").toolDefinition(resize).args("{\"width\":" + maximum + "}").build());
                assertTrue(result.isSuccess(), result.getToolOutput());
                assertEquals("numeric", result.getToolOutput());
            }
        } finally {
            server.stop(0);
        }
    }

    private static void handleResizeFixture(HttpExchange exchange, ObjectMapper json, Map<String, Object> schema)
            throws IOException {
        try (exchange) {
            JsonNode request = json.readTree(exchange.getRequestBody());
            if (!request.has("id")) {
                exchange.sendResponseHeaders(202, -1);
                return;
            }
            Map<String, Object> result = switch (request.path("method").asText()) {
                case "initialize" -> Map.of("protocolVersion", "2025-06-18", "capabilities", Map.of("tools", Map.of()),
                        "serverInfo", Map.of("name", "chrome-devtools-fixture", "version", "1.0"));
                case "tools/list" -> Map.of("tools", List.of(Map.of("name", "resize_page", "inputSchema", schema)));
                case "tools/call" -> Map.of("content", List.of(Map.of("type", "text",
                        "text", request.at("/params/arguments/width").isIntegralNumber() ? "numeric" : "non-numeric")));
                default -> throw new IllegalStateException("Unexpected MCP method: " + request.path("method").asText());
            };
            byte[] body = json.writeValueAsBytes(Map.of("jsonrpc", "2.0", "id", request.get("id"), "result", result));
            exchange.getResponseHeaders().set("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, body.length);
            exchange.getResponseBody().write(body);
        }
    }

    /** The retired flag must keep not switching anything off: there is no connection to switch. */
    @Test
    void legacyDisabledFlagDoesNotDisableInfrastructure() {
        try (AnnotationConfigApplicationContext context = context(Map.of("lingxi.mcp.enabled", "false"))) {
            context.refresh();
            assertNotNull(context.getBean(McpSessionFactory.class));
            assertInstanceOf(AgentScopeMcpProvider.class, context.getBean(ScopeMcpProvider.class));
            assertTrue(context.getBean(RecordingToolRegistry.class).registrations.isEmpty());
        }
    }

    @Test
    void applicationProviderReplacesDefault() {
        try (AnnotationConfigApplicationContext context = context(Map.of())) {
            ScopeMcpProvider custom = configuration -> McpToolScope.EMPTY;
            context.registerBean("customProvider", ScopeMcpProvider.class, () -> custom);
            context.refresh();
            assertSame(custom, context.getBean(ScopeMcpProvider.class));
            assertEquals(1, context.getBeansOfType(ScopeMcpProvider.class).size());
            // The infrastructure the custom provider may use stays available.
            assertNotNull(context.getBean(McpSessionFactory.class));
        }
    }

    /** A list published by the application replaces the defaults instead of adding to them. */
    @Test
    void applicationTransportListReplacesDefaults() {
        try (AnnotationConfigApplicationContext context =
                     context(Map.of(), UnsupportedTransportsConfiguration.class)) {
            context.refresh();

            assertFalse(context.containsBean("mcpTransportBuilders"),
                    "the default list must be skipped when the application publishes its own");
            assertSame(CUSTOM_TRANSPORTS, context.getBean("customTransportBuilders", List.class));
            // The factory is created over the application list: a second List bean of the same
            // element type makes that injection ambiguous, so a refreshed context proves the choice.
            assertNotNull(context.getBean(McpSessionFactory.class));
        }
    }

    /** Stands in for an application that publishes its own transports as a bean. */
    @Configuration(proxyBeanMethods = false)
    static class UnsupportedTransportsConfiguration {

        @Bean
        List<McpClientTransportBuilder> customTransportBuilders() {
            return CUSTOM_TRANSPORTS;
        }
    }

    /** The application list under test: it refuses every configuration, so its use is unmistakable. */
    private static final List<McpClientTransportBuilder> CUSTOM_TRANSPORTS =
            List.of(new McpClientTransportBuilder() {
                @Override
                public boolean supports(McpConfig.Conf conf) {
                    return false;
                }

                @Override
                public McpClientTransport build(McpConfig.MCP server, Workspace workspace) {
                    throw new AssertionError("the custom list must be the one in use");
                }
            });

    /** Session factory first, scheduler second: the context must not leave a live daemon behind. */
    @Test
    void closingTheContextClosesTheFactoryAndStopsTheScheduler() {
        AnnotationConfigApplicationContext context = context(Map.of());
        context.refresh();
        McpSessionFactory factory = context.getBean(McpSessionFactory.class);
        ScheduledExecutorService scheduler = context.getBean(McpAutoConfiguration.HEALTH_SCHEDULER_BEAN,
                ScheduledExecutorService.class);
        assertFalse(factory.isClosed());
        assertFalse(scheduler.isShutdown());

        context.close();

        assertTrue(factory.isClosed(), "the factory must be closed with its context");
        assertTrue(scheduler.isShutdown(), "the daemon scheduler must stop with its context");
    }

    private static AnnotationConfigApplicationContext context(Map<String, Object> properties,
                                                             Class<?>... leadingConfigurations) {
        AnnotationConfigApplicationContext context = new AnnotationConfigApplicationContext();
        context.getEnvironment().getPropertySources().addFirst(new MapPropertySource("test", properties));
        // Registered first: a configuration that replaces a default must exist when the default is
        // conditionally skipped, and conditions are evaluated in registration order.
        if (leadingConfigurations.length > 0) {
            context.register(leadingConfigurations);
        }
        context.registerBean(RecordingToolRegistry.class, () -> new RecordingToolRegistry());
        context.register(JsonConfig.class);
        context.register(McpAutoConfiguration.class);
        return context;
    }

    static class RecordingToolRegistry extends ToolRegistry {

        private final List<ToolDefinition<? extends ToolExecutor>> registrations = new ArrayList<>();

        RecordingToolRegistry() {
            super(new ArrayList<>());
        }

        @Override
        public void register(ToolDefinition<?> definition) {
            registrations.add(definition);
            super.register(definition);
        }
    }
}
