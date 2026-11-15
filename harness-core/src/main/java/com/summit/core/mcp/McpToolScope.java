package com.summit.core.mcp;

import com.summit.core.tool.ToolDefinition;
import com.summit.core.tool.ToolExecutor;
import lombok.extern.slf4j.Slf4j;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Request-scoped container of the MCP tools of one execution.
 *
 * <p>MCP tools are request data declared by {@code AgentRequest.mcpConfig} and never leak into the
 * process-wide {@link com.summit.core.tool.ToolRegistry}. Closing the scope reclaims its tools and
 * request-owned connections; shared connections remain owned by their provider.</p>
 *
 * <h2>Progressive disclosure</h2>
 *
 * <p>No tool of a scope is ever declared to the model on its own. The prompt publishes one count
 * per server, the lookup entries hand out names and schemas, and every call goes through
 * {@link McpToolGateway}, so the model-facing tool list never depends on what was looked up.</p>
 */
@Slf4j
public final class McpToolScope implements AutoCloseable {

    /**
     * Shared empty scope, used whenever a request declares no MCP server.
     */
    public static final McpToolScope EMPTY = new McpToolScope();

    private final Map<String, ToolDefinition<?>> tools = new ConcurrentHashMap<>();
    /**
     * Server each tool was discovered on, keyed by tool name.
     */
    private final Map<String, String> toolServers = new ConcurrentHashMap<>();
    /**
     * Description each server declared in the request configuration, keyed by server name.
     */
    private final Map<String, String> serverDescriptions = new ConcurrentHashMap<>();
    private final List<McpSession> sessions = new ArrayList<>();

    private McpToolScope() {
    }

    /**
     * Creates a scope over the given sessions, materializing their tools now.
     * <p>Tool discovery may perform I/O; transport startup and protocol initialization belong to the session provider.</p>
     */
    public static McpToolScope of(List<McpSession> sessions) {
        if (sessions == null || sessions.isEmpty()) {
            return EMPTY;
        }
        McpToolScope scope = new McpToolScope();
        for (McpSession session : sessions) {
            if (!scope.adopt(session) && session.type() != McpSessionType.REUSE) {
                scope.closeQuietly(session);
            }
        }
        return scope;
    }

    /**
     * @return {@code false} when the session cannot list its tools, so the caller drops it.
     */
    private boolean adopt(McpSession session) {
        List<ToolDefinition<? extends ToolExecutor>> discovered;
        try {
            discovered = session.tools();
        } catch (Exception failure) {
            // Only the root cause is logged: the wrapper exceptions the transports and the
            // session add carry no diagnosis, and the class name alone cannot tell an
            // unreachable endpoint from a closed client or a refused launch command.
            Throwable root = rootCauseOf(failure);
            log.warn("MCP server {} tool discovery failed ({}: {}); continuing without its tools",
                    session.name(), root.getClass().getSimpleName(), root.getMessage());
            return false;
        }
        for (ToolDefinition<? extends ToolExecutor> tool : discovered) {
            if (tools.putIfAbsent(tool.name(), tool) != null) {
                log.warn("MCP tool {} is provided by more than one server; keeping the first", tool.name());
            } else {
                toolServers.put(tool.name(), session.name());
            }
        }
        String description = session.description();
        serverDescriptions.put(session.name(), description == null ? "" : description);
        sessions.add(session);
        return true;
    }

    /**
     * The tool of this request carrying the given name, or {@code null}.
     */
    public ToolDefinition<?> getTool(String name) {
        return name == null || name.isBlank() ? null : tools.get(name);
    }

    /**
     * Every tool this request declared.
     */
    public Collection<ToolDefinition<?>> getTools() {
        return Collections.unmodifiableCollection(tools.values());
    }

    /**
     * The servers of this request as prompt summaries, server-ordered — one entry each, carrying the
     * description its configuration declared and the number of tools it contributed. This is what
     * tells the model which servers exist before it looks any tool up.
     */
    public List<McpResume> resumes() {
        Map<String, Integer> counts = new TreeMap<>();
        toolServers.values().forEach(server -> counts.merge(server, 1, Integer::sum));
        return counts.entrySet().stream()
                .map(entry -> new McpResume(entry.getKey(), descriptionOf(entry.getKey()), entry.getValue()))
                .toList();
    }

    /**
     * The description declared for one server, or an empty string when its configuration has none.
     */
    private String descriptionOf(String server) {
        String description = serverDescriptions.get(server);
        return description == null ? "" : description;
    }

    /**
     * The server the named tool belongs to, or {@code null} when this scope does not hold it.
     */
    public String serverOf(String toolName) {
        return toolName == null ? null : toolServers.get(toolName);
    }

    /**
     * The tools of one server, name-ordered, or every tool of this request when {@code serverName}
     * is blank — a missing argument must not turn a lookup into a failed round. An unknown server
     * name yields an empty list.
     */
    public List<ToolDefinition<?>> toolsOfServer(String serverName) {
        String filter = serverName == null ? "" : serverName.strip();
        return tools.values().stream()
                .filter(tool -> filter.isEmpty() || filter.equals(serverOf(tool.name())))
                .sorted(Comparator.comparing(ToolDefinition::name))
                .toList();
    }

    /**
     * Names of every server this request connected to, server-ordered.
     */
    public List<String> serverNames() {
        return sessions.stream().map(McpSession::name).sorted().toList();
    }

    public boolean isEmpty() {
        return tools.isEmpty();
    }

    /**
     * Releases the tools and closes every request-owned MCP connection. Shared sessions
     * ({@code AUTO}, {@code REUSE}) outlive the scope — their provider owns them.
     */
    @Override
    public void close() {
        tools.clear();
        toolServers.clear();
        serverDescriptions.clear();
        List<McpSession> toClose = new ArrayList<>(sessions);
        sessions.clear();
        toClose.stream().filter(McpSession::requireAutoClose).forEach(this::closeQuietly);
    }

    /** Unwraps wrapper exceptions so the transport-level reason is reached. */
    private static Throwable rootCauseOf(Throwable failure) {
        Throwable current = failure;
        while (current.getCause() != null && current.getCause() != current) {
            current = current.getCause();
        }
        return current;
    }

    private void closeQuietly(McpSession session) {
        try {
            session.close();
        } catch (Exception failure) {
            // A broken connection must not prevent the others from closing.
            log.warn("MCP session {} close failed ({})",
                    session.name(), failure.getClass().getSimpleName());
        }
    }
}
