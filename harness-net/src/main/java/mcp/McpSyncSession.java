package mcp;

import com.summit.core.mcp.McpSession;
import com.summit.core.mcp.McpSessionType;
import com.summit.core.tool.ToolDefinition;
import com.summit.core.tool.ToolExecutor;

import java.util.List;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * An initialized MCP session with the tools the server published.
 *
 * <p>The session is created by {@link McpSessionFactory} only after the connection completed its
 * handshake and its tools were discovered, so {@link #tools()} never performs I/O: the definitions
 * are already in place, and the runtime may ask for them as often as it likes without another round
 * trip. Reconnects restore the connection, never the tool table — a server whose tool list changes
 * while the process runs would otherwise make the model's schema drift mid-conversation.</p>
 *
 * <p>Closing is idempotent and releases both the connection and the factory's registration, so a
 * scope, a pool replacement and a shutdown may all close the same session.</p>
 */
public final class McpSyncSession implements McpSession {

    private final String name;
    private final String description;
    private final McpSessionType type;
    private final McpResilientConnection connection;
    private final List<ToolDefinition<? extends ToolExecutor>> tools;
    private final List<String> serverToolNames;
    private final String negotiatedProtocolVersion;
    private final Runnable releaseAction;
    private final AtomicBoolean closed = new AtomicBoolean(false);

    /**
     * @param name                      server name as declared by the configuration
     * @param description               description to publish; never null
     * @param type                      lifetime contract of this session
     * @param connection                connection the tools read their client from
     * @param tools                     already converted tool definitions
     * @param serverToolNames           tool names as the server declared them, sorted ascending
     * @param negotiatedProtocolVersion protocol version the server agreed on
     * @param releaseAction             called once on close, e.g. to drop the factory registration
     */
    public McpSyncSession(String name, String description, McpSessionType type,
                          McpResilientConnection connection,
                          List<ToolDefinition<? extends ToolExecutor>> tools,
                          List<String> serverToolNames, String negotiatedProtocolVersion,
                          Runnable releaseAction) {
        this.name = Objects.requireNonNull(name, "name must not be null");
        this.description = description == null ? "" : description;
        this.type = type == null ? McpSessionType.PER_REQUEST : type;
        this.connection = Objects.requireNonNull(connection, "connection must not be null");
        this.tools = List.copyOf(tools == null ? List.of() : tools);
        this.serverToolNames = List.copyOf(serverToolNames == null ? List.of() : serverToolNames);
        this.negotiatedProtocolVersion = negotiatedProtocolVersion == null ? "" : negotiatedProtocolVersion;
        this.releaseAction = releaseAction == null ? () -> { } : releaseAction;
    }

    @Override
    public String name() {
        return name;
    }

    @Override
    public McpSessionType type() {
        return type;
    }

    @Override
    public String description() {
        return description;
    }

    @Override
    public List<ToolDefinition<? extends ToolExecutor>> tools() {
        return tools;
    }

    @Override
    public List<String> serverToolNames() {
        return serverToolNames;
    }

    /**
     * @return the protocol version the server agreed on, or an empty string when the handshake did
     *         not report one
     */
    public String negotiatedProtocolVersion() {
        return negotiatedProtocolVersion;
    }

    @Override
    public void checkHealth() {
        connection.checkHealth();
    }

    @Override
    public void close() {
        if (!closed.compareAndSet(false, true)) {
            return;
        }
        try {
            connection.close();
        } finally {
            releaseAction.run();
        }
    }

    /** @return whether this session has been closed already */
    public boolean isClosed() {
        return closed.get();
    }
}
