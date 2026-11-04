package mcp;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.summit.core.conf.McpConfig;
import com.summit.core.conf.McpTransport;
import com.summit.core.mcp.McpSession;
import com.summit.core.mcp.McpSessionType;
import com.summit.core.mcp.McpToolScope;
import com.summit.core.runtime.workspace.Workspace;
import io.modelcontextprotocol.spec.McpClientTransport;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Provider behaviour, exercisable without a Spring container and without a network.
 *
 * <p>The transport builder is the seam: it decides what a server "answers", so an unreachable or
 * broken server is an ordinary case here rather than a mocked exception. Closes are counted on the
 * transports, which is the only observation point a closed session leaves behind.</p>
 */
@Timeout(60)
class AgentScopeMcpProviderTest {

    private static final String PREFIX = AgentScopeMcpProvider.NAME_MCP_PREFIX;

    @Test
    void connectionFailureDoesNotHideHealthyServerAndOwnedSessionClosesOnce() {
        List<FakeMcpTransport> transports = new ArrayList<>();
        AgentScopeMcpProvider provider = providerOf(server -> {
            if (server.name().equals("broken")) {
                throw new IllegalStateException("connection failed");
            }
            return new FakeMcpTransport();
        }, new AtomicInteger(), transports);

        McpToolScope scope = provider.openScope(config(server("broken"), server("healthy")));

        assertEquals("mcp_search", scope.getTool("mcp_search").name());
        assertEquals(1, scope.getTools().size());
        assertEquals(0, transports.getFirst().closeCount());
        scope.close();
        scope.close();
        assertEquals(1, transports.getFirst().closeCount(), "a scope closes its request-owned session once");
    }

    @Test
    void eachRequestGetsItsOwnScopeAndOwnedConnection() {
        AtomicInteger builds = new AtomicInteger();
        List<FakeMcpTransport> transports = new ArrayList<>();
        AgentScopeMcpProvider provider = providerOf(server -> new FakeMcpTransport(), builds, transports);
        McpConfig configuration = config(server("healthy"));

        McpToolScope first = provider.openScope(configuration);
        McpToolScope second = provider.openScope(configuration);

        assertNotSame(first, second);
        assertEquals(2, builds.get());
        assertEquals("mcp_search", first.getTool("mcp_search").name());
        assertEquals("mcp_search", second.getTool("mcp_search").name());

        first.close();
        assertEquals(1, transports.get(0).closeCount());
        assertEquals(0, transports.get(1).closeCount());
        assertNull(first.getTool("mcp_search"));
        assertEquals("mcp_search", second.getTool("mcp_search").name());

        second.close();
        assertEquals(1, transports.get(1).closeCount());
    }

    @Test
    void emptyConfigurationOpensNoConnection() {
        AtomicInteger builds = new AtomicInteger();
        AgentScopeMcpProvider provider = providerOf(server -> new FakeMcpTransport(), builds, new ArrayList<>());

        assertSame(McpToolScope.EMPTY, provider.openScope(new McpConfig()));
        assertSame(McpToolScope.EMPTY, provider.openScope(config()));
        assertEquals(0, builds.get());
    }

    @Test
    void duplicateToolNamesAcrossRequestsDoNotCollide() {
        AgentScopeMcpProvider provider = providerOf(server -> new FakeMcpTransport(), new AtomicInteger(),
                new ArrayList<>());

        try (McpToolScope first = provider.openScope(config(server("healthy")))) {
            assertEquals("mcp_search", first.getTool("mcp_search").name());
            try (McpToolScope second = provider.openScope(config(server("healthy")))) {
                assertEquals("mcp_search", second.getTool("mcp_search").name());
            }
            assertEquals("mcp_search", first.getTool("mcp_search").name());
        }
    }

    /** A server that cannot list its tools is dropped; the healthy one keeps its tools. */
    @Test
    void discoveryFailureDropsOnlyThatServer() {
        List<FakeMcpTransport> transports = new ArrayList<>();
        AgentScopeMcpProvider provider = providerOf(server -> {
            FakeMcpTransport transport = new FakeMcpTransport();
            if (server.name().equals("broken")) {
                transport.failToolList(true);
            }
            return transport;
        }, new AtomicInteger(), transports);

        try (McpToolScope scope = provider.openScope(config(server("broken"), server("healthy")))) {
            assertEquals(1, scope.getTools().size());
            assertEquals("mcp_search", scope.getTool("mcp_search").name());
        }
        assertEquals(1, transports.getFirst().closeCount(),
                "the failed server's connection must still be released");
    }

    /** The description falls back to the server's own instructions when none is configured. */
    @Test
    void descriptionFallsBackToServerInstructions() {
        AgentScopeMcpProvider provider = providerOf(server -> new FakeMcpTransport().instructions("own words"),
                new AtomicInteger(), new ArrayList<>());

        try (McpToolScope scope = provider.openScope(config(server("healthy")))) {
            assertEquals("own words", scope.resumes().getFirst().description());
        }
    }

    @Test
    void configuredDescriptionWinsOverServerInstructions() {
        McpConfig.MCP described = new McpConfig.MCP("described", "operator wrote this", McpTransport.STREAMABLE_HTTP,
                new McpConfig.StreamableHttp("https://described.example/mcp", Map.of(),
                        Duration.ofSeconds(3), Duration.ofSeconds(4)), 100);
        AgentScopeMcpProvider provider = providerOf(server -> new FakeMcpTransport().instructions("own words"),
                new AtomicInteger(), new ArrayList<>());

        try (McpToolScope scope = provider.openScope(config(described))) {
            assertEquals("operator wrote this", scope.resumes().getFirst().description());
        }
    }

    /** The session reports the server's own tool names, without the prefix the model sees. */
    @Test
    void theSessionPublishesServerToolNamesAndTheLifetimeContract() {
        McpSessionFactory factory = factoryOf(server -> new FakeMcpTransport().tools(List.of(
                Map.of("name", "echo", "inputSchema", Map.of("type", "object")),
                Map.of("name", "add", "inputSchema", Map.of("type", "object")))), new AtomicInteger());

        try (McpSession session = factory.openSession(server("healthy"), null, McpSessionType.REUSE, PREFIX)) {
            assertEquals(McpSessionType.REUSE, session.type());
            assertFalse(session.requireAutoClose(), "a shared session is released by its provider");
            assertEquals(List.of("add", "echo"), session.serverToolNames(), "sorted, unprefixed");
            // The published order follows the server's own list; only the names are rewritten.
            assertEquals(List.of("mcp_echo", "mcp_add"),
                    session.tools().stream().map(tool -> tool.name()).toList());
            session.checkHealth();
            assertFalse(session.description().isEmpty());
        }

        try (McpSession requestOwned = factory.openSession(server("healthy"))) {
            assertTrue(requestOwned.requireAutoClose());
        }
        factory.close();
    }

    @Test
    void anUnprefixedSessionKeepsTheServerNames() {
        McpSessionFactory factory = factoryOf(server -> new FakeMcpTransport(), new AtomicInteger());

        try (McpSession session = factory.openSession(server("healthy"))) {
            assertEquals("search", session.tools().getFirst().name());
            assertEquals(List.of("search"), session.serverToolNames());
        }
        factory.close();
    }

    /** A closed factory must refuse new sessions instead of leaking connections. */
    @Test
    void aClosedFactoryRefusesNewSessions() {
        McpSessionFactory factory = factoryOf(server -> new FakeMcpTransport(), new AtomicInteger());
        factory.close();

        assertTrue(factory.isClosed());
        org.junit.jupiter.api.Assertions.assertThrows(IllegalStateException.class,
                () -> factory.openSession(server("healthy")));
    }

    private static AgentScopeMcpProvider providerOf(ServerTransportFactory transportFactory,
                                                    AtomicInteger builds,
                                                    List<FakeMcpTransport> transports) {
        return new AgentScopeMcpProvider(factoryOf(transportFactory, builds, transports));
    }

    private static McpSessionFactory factoryOf(ServerTransportFactory transportFactory, AtomicInteger builds) {
        return factoryOf(transportFactory, builds, new ArrayList<>(), new ObjectMapper());
    }

    private static McpSessionFactory factoryOf(ServerTransportFactory transportFactory, AtomicInteger builds,
                                               List<FakeMcpTransport> transports) {
        return factoryOf(transportFactory, builds, transports, new ObjectMapper());
    }

    private static McpSessionFactory factoryOf(ServerTransportFactory transportFactory, AtomicInteger builds,
                                               List<FakeMcpTransport> transports, ObjectMapper json) {
        McpClientTransportBuilder builder = new McpClientTransportBuilder() {
            @Override
            public boolean supports(McpConfig.Conf conf) {
                return true;
            }

            @Override
            public McpClientTransport build(McpConfig.MCP server, Workspace workspace) {
                builds.incrementAndGet();
                McpClientTransport transport = transportFactory.create(server);
                if (transport instanceof FakeMcpTransport fake) {
                    transports.add(fake);
                }
                return transport;
            }
        };
        return new McpSessionFactory(McpTestSupport.fastSettings(3), List.of(builder), null, json);
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

    /** Supplies the transport of one server; throwing simulates an unreachable endpoint. */
    @FunctionalInterface
    private interface ServerTransportFactory {
        McpClientTransport create(McpConfig.MCP server);
    }
}
