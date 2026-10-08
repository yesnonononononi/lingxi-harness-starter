package com.summit.adapter.langchain4j.mcp;

import com.summit.core.conf.McpConfig;
import com.summit.core.conf.McpTransport;
import com.summit.core.mcp.McpToolScope;
import dev.langchain4j.agent.tool.ToolSpecification;
import dev.langchain4j.mcp.client.McpClient;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Proxy;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

/** Provider behavior is usable and tested without a Spring container. */
class AgentScopeMcpProviderTest {
    @Test
    void connectionFailureDoesNotHideHealthyServerAndOwnedClientClosesOnce() {
        AtomicInteger closed = new AtomicInteger();
        AgentScopeMcpProvider provider = new AgentScopeMcpProvider(new McpClientFactory() {
            @Override
            public McpClient create(String key, McpConfig.MCP server) {
                if (key.equals("broken")) throw new IllegalStateException("connection failed");
                assertEquals("healthy", key);
                return fakeClient(closed);
            }
        });
        McpToolScope scope = provider.openScope(config(server("broken"), server("healthy")));
        assertNotNull(scope.getTool("mcp_search"));
        assertEquals(1, scope.getTools().size());
        assertEquals(0, closed.get());
        scope.close();
        scope.close();
        assertEquals(1, closed.get());
    }

    @Test
    void eachRequestGetsItsOwnScopeAndOwnedConnection() {
        AtomicInteger created = new AtomicInteger();
        AtomicInteger closed = new AtomicInteger();
        AgentScopeMcpProvider provider = new AgentScopeMcpProvider(new McpClientFactory() {
            @Override
            public McpClient create(String key, McpConfig.MCP server) {
                created.incrementAndGet();
                return fakeClient(closed);
            }
        });
        McpConfig configuration = config(server("healthy"));
        McpToolScope first = provider.openScope(configuration);
        McpToolScope second = provider.openScope(configuration);
        assertNotSame(first, second);
        assertEquals(2, created.get());
        assertNotNull(first.getTool("mcp_search"));
        assertNotNull(second.getTool("mcp_search"));
        first.close();
        assertEquals(1, closed.get());
        assertNull(first.getTool("mcp_search"));
        assertNotNull(second.getTool("mcp_search"));
        second.close();
        assertEquals(2, closed.get());
    }

    @Test
    void emptyConfigurationOpensNoConnection() {
        AtomicInteger created = new AtomicInteger();
        AgentScopeMcpProvider provider = new AgentScopeMcpProvider(new McpClientFactory() {
            @Override
            public McpClient create(String key, McpConfig.MCP server) {
                created.incrementAndGet();
                return fakeClient(new AtomicInteger());
            }
        });
        assertSame(McpToolScope.EMPTY, provider.openScope(new McpConfig()));
        assertSame(McpToolScope.EMPTY, provider.openScope(config()));
        assertEquals(0, created.get());
    }

    @Test
    void duplicateToolNamesAcrossRequestsDoNotCollide() {
        AgentScopeMcpProvider provider = new AgentScopeMcpProvider(new McpClientFactory() {
            @Override
            public McpClient create(String key, McpConfig.MCP server) {
                return fakeClient(new AtomicInteger());
            }
        });
        try (McpToolScope first = provider.openScope(config(server("healthy")))) {
            assertDoesNotThrow(() -> provider.openScope(config(server("healthy"))).close());
            assertNotNull(first.getTool("mcp_search"));
        }
    }

    @Test
    void discoveryFailureClosesOnlyFailedClient() {
        AtomicInteger failedClosed = new AtomicInteger();
        AtomicInteger healthyClosed = new AtomicInteger();
        AgentScopeMcpProvider provider = new AgentScopeMcpProvider(new McpClientFactory() {
            @Override
            public McpClient create(String key, McpConfig.MCP server) {
                if (key.equals("broken")) return (McpClient) Proxy.newProxyInstance(
                        McpClient.class.getClassLoader(), new Class<?>[]{McpClient.class},
                        (proxy, method, arguments) -> switch (method.getName()) {
                            case "listTools" -> throw new IllegalStateException("discovery failed");
                            case "close" -> { failedClosed.incrementAndGet(); yield null; }
                            default -> throw new UnsupportedOperationException(method.getName());
                        });
                return fakeClient(healthyClosed);
            }
        });
        try (McpToolScope scope = provider.openScope(config(server("broken"), server("healthy")))) {
            assertEquals(1, failedClosed.get());
            assertEquals(0, healthyClosed.get());
            assertEquals(1, scope.getTools().size());
        }
        assertEquals(1, failedClosed.get());
        assertEquals(1, healthyClosed.get());
    }

    private static McpConfig config(McpConfig.MCP... servers) {
        McpConfig config = new McpConfig();
        config.setMcp(List.of(servers));
        return config;
    }

    private static McpConfig.MCP server(String name) {
        return new McpConfig.MCP(name, null, McpTransport.STREAMABLE_HTTP,
                new McpConfig.StreamableHttp("https://" + name + ".example/mcp", Map.of(),
                        Duration.ofSeconds(3), Duration.ofSeconds(4)), 100);
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
