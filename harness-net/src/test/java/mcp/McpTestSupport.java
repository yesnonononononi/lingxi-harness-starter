package mcp;

import com.summit.core.conf.McpConfig;
import com.summit.core.conf.McpTransport;
import com.summit.core.runtime.workspace.Workspace;
import io.modelcontextprotocol.client.McpSyncClient;
import io.modelcontextprotocol.spec.McpClientTransport;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Supplier;

/**
 * Small factories shared by the MCP tests.
 *
 * <p>They keep the intent of a test readable: a test that is about reconnect timing should not
 * repeat how a settings record is built, and the "fast" settings are the only ones that make a
 * three-attempt reconnect finish inside a test.
 */
final class McpTestSupport {

    private McpTestSupport() {
    }

    /** Settings whose backoff and reconnect floor are milliseconds, so retries are observable fast. */
    static McpClientSettings fastSettings(int reconnectAttempts) {
        return new McpClientSettings(McpClientSettings.DEFAULT_CLIENT_NAME,
                McpClientSettings.DEFAULT_CLIENT_VERSION, Duration.ofSeconds(3), Duration.ofSeconds(3),
                false, Duration.ofMillis(5), Duration.ofMillis(1), reconnectAttempts, 1L, 2, 2L);
    }

    static McpConfig.MCP httpServer(String name, String url) {
        return new McpConfig.MCP(name, null, McpTransport.STREAMABLE_HTTP,
                new McpConfig.StreamableHttp(url, Map.of(), Duration.ofSeconds(3), Duration.ofSeconds(3)), 1000);
    }

    static McpConfig.MCP stdioServer(String name, List<String> command, String workDir) {
        return new McpConfig.MCP(name, null, McpTransport.STDIO,
                new McpConfig.Stdio(command, Map.of("VISIBLE", "yes"), workDir,
                        Duration.ofSeconds(10), Duration.ofSeconds(10)), 1000);
    }

    /** Connects the given transport and returns a handle whose close is counted. */
    static McpClientHandle handleOf(McpClientTransport transport, McpClientSettings settings,
                                    McpConfig.MCP server, AtomicInteger closes) {
        McpSyncClient client = McpSessionFactory.connect(transport, settings, server);
        return new CountingHandle(client, closes);
    }

    /** A connection over a single hand-made handle; the factory is not involved. */
    static McpResilientConnection connectionOf(Supplier<McpClientHandle> factory) {
        McpResilientConnection connection = new McpResilientConnection(factory, fastSettings(3), null);
        connection.start();
        return connection;
    }

    /** A transport builder that answers with the given transport and counts how often it was asked. */
    static McpClientTransportBuilder builderOf(Supplier<McpClientTransport> transports, AtomicInteger builds) {
        return new McpClientTransportBuilder() {
            @Override
            public boolean supports(McpConfig.Conf conf) {
                return true;
            }

            @Override
            public McpClientTransport build(McpConfig.MCP server, Workspace workspace) {
                builds.incrementAndGet();
                return transports.get();
            }
        };
    }

    /** Handle whose only job in the tests is to expose a client and count its closes. */
    static final class CountingHandle implements McpClientHandle {

        private final McpSyncClient client;
        private final AtomicInteger closes;
        private final AtomicInteger closeCalls = new AtomicInteger();

        CountingHandle(McpSyncClient client, AtomicInteger closes) {
            this.client = client;
            this.closes = closes;
        }

        @Override
        public McpSyncClient client() {
            return client;
        }

        @Override
        public void close() {
            if (closeCalls.incrementAndGet() == 1 && closes != null) {
                closes.incrementAndGet();
            }
            client.close();
        }
    }
}
