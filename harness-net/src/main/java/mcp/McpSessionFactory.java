package mcp;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.summit.core.conf.McpConfig;
import com.summit.core.mcp.McpSession;
import com.summit.core.mcp.McpSessionType;
import com.summit.core.runtime.workspace.Workspace;
import com.summit.core.tool.ToolDefinition;
import com.summit.core.tool.ToolExecutor;
import io.modelcontextprotocol.client.McpClient;
import io.modelcontextprotocol.client.McpSyncClient;
import io.modelcontextprotocol.spec.McpClientTransport;
import io.modelcontextprotocol.spec.McpSchema;
import lombok.extern.slf4j.Slf4j;
import mcp.json.PassthroughJsonSchemaValidator;

import java.time.Duration;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * The single entry point for opening MCP sessions.
 *
 * <p>One call does everything in a fixed order — validate, choose a transport, connect and
 * initialize, publish the tools, schedule the health check — so no caller has to reproduce that
 * sequence, and a failure at any step releases whatever was already acquired.</p>
 *
 * <p>Close semantics: every connection this factory created is registered here, which makes
 * {@link #close()} the backstop for sessions that were never closed by their owner. Sessions remove
 * themselves from the registry when they close, so the registry stays proportional to what is
 * actually open.</p>
 */
@Slf4j
public final class McpSessionFactory implements AutoCloseable {

    private final McpClientSettings settings;
    private final List<McpClientTransportBuilder> builders;
    private final ScheduledExecutorService healthScheduler;
    private final ObjectMapper json;
    private final Set<McpResilientConnection> connections = ConcurrentHashMap.newKeySet();
    private final AtomicBoolean closed = new AtomicBoolean(false);

    /**
     * @param settings        client tuning; null falls back to {@link McpClientSettings#defaults()}
     * @param builders        transport builders to choose from; an empty list makes every open fail
     *                        with a message naming the unsupported connection kind
     * @param healthScheduler daemon scheduler shared by every connection; null disables background
     *                        health checks, which is only useful in tests
     * @param json            application mapper, used for tool schemas and tool arguments
     */
    public McpSessionFactory(McpClientSettings settings, List<McpClientTransportBuilder> builders,
                             ScheduledExecutorService healthScheduler, ObjectMapper json) {
        this.settings = settings == null ? McpClientSettings.defaults() : settings;
        this.builders = builders == null ? List.of() : List.copyOf(builders);
        this.healthScheduler = healthScheduler;
        this.json = json == null ? new ObjectMapper() : json;
    }

    /**
     * Opens a request-owned session whose tools are published without a prefix.
     *
     * @param server server to open
     * @return an initialized session that its owner must close
     */
    public McpSession openSession(McpConfig.MCP server) {
        return openSession(server, null, McpSessionType.PER_REQUEST, "");
    }

    /**
     * Opens a request-owned session in the given workspace.
     *
     * @param server    server to open
     * @param workspace workspace of the request, may be null
     * @return an initialized session that its owner must close
     */
    public McpSession openSession(McpConfig.MCP server, Workspace workspace) {
        return openSession(server, workspace, McpSessionType.PER_REQUEST, "");
    }

    /**
     * Opens one session.
     *
     * @param server     server to open
     * @param workspace  workspace of the request, may be null
     * @param type       lifetime contract of the new session
     * @param namePrefix prefix of the published tool names; blank means unprefixed
     * @return an initialized session whose lifetime follows {@code type}
     * @throws IllegalArgumentException when the server entry itself is invalid
     * @throws IllegalStateException    when the factory is closed or the connection cannot be
     *                                 established; the cause carries the transport's diagnosis
     */
    public McpSession openSession(McpConfig.MCP server, Workspace workspace, McpSessionType type,
                                  String namePrefix) {
        if (closed.get()) {
            throw new IllegalStateException("MCP session factory is closed");
        }
        McpServerValidator.validate(server);
        McpWorkspaceSupport.validate(server, workspace);

        McpClientTransportBuilder builder = builderOf(server);
        McpResilientConnection connection = new McpResilientConnection(server.name(),
                () -> buildHandle(server, workspace, builder), settings, healthScheduler);
        connections.add(connection);
        try {
            connection.start();
            connection.applyReconnectInterval(settings.resolveReconnectInterval(server.conf()));
            McpSchema.InitializeResult initialization = connection.initializationResult();
            McpToolConverter converter = new McpToolConverter(McpToolOptions.withPrefix(namePrefix, server), json);
            List<ToolDefinition<? extends ToolExecutor>> tools = new ArrayList<>();
            List<String> serverToolNames = new ArrayList<>();
            for (McpSchema.Tool remoteTool : listTools(server, connection)) {
                tools.add(converter.convert(remoteTool, connection));
                serverToolNames.add(remoteTool.name());
            }
            serverToolNames.sort(Comparator.naturalOrder());
            McpSyncSession session = new McpSyncSession(server.name(), resolveDescription(server, initialization),
                    type, connection, tools, serverToolNames, resolveProtocolVersion(initialization),
                    () -> connections.remove(connection));
            connection.scheduleHealthCheck(settings.resolveHealthCheckInterval(server.conf()),
                    settings.resolveAutoHealthCheck(server.conf()));
            return session;
        } catch (RuntimeException | Error failure) {
            connections.remove(connection);
            connection.close();
            throw failure;
        }
    }

    /**
     * Closes every connection still registered. Sessions already closed by their owner are gone from
     * the registry, so this only releases what nobody released; the health-check scheduler belongs
     * to the caller and is left alone.
     */
    @Override
    public void close() {
        if (!closed.compareAndSet(false, true)) {
            return;
        }
        List<McpResilientConnection> open = new ArrayList<>(connections);
        connections.clear();
        for (McpResilientConnection connection : open) {
            try {
                connection.close();
            } catch (RuntimeException failure) {
                log.warn("MCP connection close failed ({})", failure.getClass().getSimpleName());
            }
        }
    }

    /** @return whether {@link #close()} has run */
    public boolean isClosed() {
        return closed.get();
    }

    /** @return the tuning this factory applies */
    public McpClientSettings settings() {
        return settings;
    }

    /** Picks the first builder that accepts the connection settings of the server. */
    private McpClientTransportBuilder builderOf(McpConfig.MCP server) {
        for (McpClientTransportBuilder builder : builders) {
            if (builder.supports(server.conf())) {
                return builder;
            }
        }
        throw new IllegalArgumentException("MCP server '" + server.name()
                + "': no transport builder supports " + server.conf().getClass().getSimpleName()
                + "; SSE is not supported, use streamable-http");
    }

    /**
     * Creates one connected and initialized handle. Called once per connection attempt: a reconnect
     * must build a brand-new transport, because a closed stdio pipe or a discarded sandbox launch
     * cannot be reused.
     */
    private McpClientHandle buildHandle(McpConfig.MCP server, Workspace workspace,
                                        McpClientTransportBuilder builder) {
        McpClientTransport transport = builder.build(server, workspace);
        McpSyncClient client = null;
        try {
            client = connect(transport, settings, server);
            warnOnProtocolVersionMismatch(server, client);
            return new SingleClientHandle(client);
        } catch (RuntimeException | Error failure) {
            if (client != null) {
                closeClient(client, server.name());
            } else {
                closeTransport(transport, server.name());
            }
            throw failure;
        }
    }

    /**
     * Builds a client over the given transport and completes protocol initialization.
     *
     * <p>Package-visible so that tests can drive a fake transport through the exact same client
     * configuration the production path uses.</p>
     *
     * @param transport transport to connect
     * @param settings  client tuning
     * @param server    server whose timeouts apply
     * @return an initialized client
     */
    static McpSyncClient connect(McpClientTransport transport, McpClientSettings settings,
                                 McpConfig.MCP server) {
        McpClientSettings effective = settings == null ? McpClientSettings.defaults() : settings;
        McpSyncClient client = McpClient.sync(transport)
                .clientInfo(McpSchema.Implementation
                        .builder(effective.clientName(), effective.clientVersion())
                        .build())
                .initializationTimeout(effective.resolveInitializationTimeout(server))
                .requestTimeout(effective.resolveRequestTimeout(server))
                // Injected explicitly: without it the builder falls back to a ServiceLoader lookup
                // for a JSON-schema validator, which is not on the class path.
                .jsonSchemaValidator(new PassthroughJsonSchemaValidator())
                // Tool schemas are never validated and no output schema is cached, so the call path
                // must not trigger an extra tools/list after initialization.
                .enableCallToolSchemaCaching(false)
                .build();
        client.initialize();
        return client;
    }

    /**
     * Reports a stdio protocol version the server did not accept.
     *
     * <p>stdio has no configuration entry for the version: the SDK sends the newest version it
     * supports. A configured value is therefore an assertion, not a request — it is compared after
     * the handshake, and a mismatch is reported instead of being silently ignored. It is never an
     * error: refusing the connection would lose a working server over a cosmetic setting.</p>
     */
    private void warnOnProtocolVersionMismatch(McpConfig.MCP server, McpSyncClient client) {
        if (!(server.conf() instanceof McpConfig.Stdio)) {
            return;
        }
        String configured = settings.resolveProtocolVersion(server.conf());
        if (configured.isEmpty()) {
            return;
        }
        McpSchema.InitializeResult initialization = client.getCurrentInitializationResult();
        String negotiated = initialization == null || initialization.protocolVersion() == null
                ? "" : initialization.protocolVersion();
        if (!configured.equals(negotiated)) {
            log.warn("MCP server {} is configured with protocol-version {} but negotiated {}; "
                    + "stdio cannot enforce a version", server.name(), configured, negotiated);
        }
    }

    /** @return the tools the server publishes, never null */
    private List<McpSchema.Tool> listTools(McpConfig.MCP server, McpResilientConnection connection) {
        McpSchema.ListToolsResult result = connection.client().listTools();
        if (result == null || result.tools() == null) {
            log.warn("MCP server {} published no tool list", server.name());
            return List.of();
        }
        return result.tools();
    }

    /** Configuration description first — it is what the operator wrote — then the server's own. */
    private String resolveDescription(McpConfig.MCP server, McpSchema.InitializeResult initialization) {
        if (server.description() != null && !server.description().isBlank()) {
            return server.description();
        }
        String instructions = initialization == null ? null : initialization.instructions();
        return instructions == null ? "" : instructions;
    }

    private String resolveProtocolVersion(McpSchema.InitializeResult initialization) {
        return initialization == null || initialization.protocolVersion() == null
                ? "" : initialization.protocolVersion();
    }

    private void closeTransport(McpClientTransport transport, String serverName) {
        try {
            transport.closeGracefully().block(Duration.ofSeconds(10));
        } catch (RuntimeException failure) {
            log.warn("MCP server {} transport close failed ({})", serverName,
                    failure.getClass().getSimpleName());
            try {
                transport.close();
            } catch (RuntimeException ignored) {
                log.warn("MCP server {} transport immediate close failed ({})", serverName,
                        ignored.getClass().getSimpleName());
            }
        }
    }

    /** Graceful close first, immediate close as the fallback: the documented shutdown contract. */
    private void closeClient(McpSyncClient client, String serverName) {
        try {
            if (!client.closeGracefully()) {
                log.debug("MCP server {} client did not close gracefully; closing immediately", serverName);
                client.close();
            }
        } catch (RuntimeException failure) {
            log.warn("MCP server {} client close failed ({})", serverName,
                    failure.getClass().getSimpleName());
            try {
                client.close();
            } catch (RuntimeException ignored) {
                log.warn("MCP server {} client immediate close failed ({})", serverName,
                        ignored.getClass().getSimpleName());
            }
        }
    }

    /** One handle wrapping one client; closing it releases that client immediately. */
    private static final class SingleClientHandle implements McpClientHandle {

        private final McpSyncClient client;
        private final AtomicBoolean released = new AtomicBoolean(false);

        private SingleClientHandle(McpSyncClient client) {
            this.client = Objects.requireNonNull(client, "client must not be null");
        }

        @Override
        public McpSyncClient client() {
            return client;
        }

        @Override
        public void close() {
            if (!released.compareAndSet(false, true)) {
                return;
            }
            client.close();
        }
    }
}
