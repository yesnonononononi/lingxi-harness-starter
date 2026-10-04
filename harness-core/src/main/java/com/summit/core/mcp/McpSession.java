package com.summit.core.mcp;

import com.summit.core.tool.ToolDefinition;
import com.summit.core.tool.ToolExecutor;

import java.util.List;

/**
 * One MCP server connection as seen by the core.
 *
 * <p>This is the dependency-inversion seam of the MCP extension: core knows a session only as
 * "a named provider of tools that can be closed", while the concrete implementation lives in
 * the transport adapter and wraps its own client.</p>
 *
 * <p>The {@link #type() type} decides who owns the connection's lifetime: a
 * {@link McpSessionType#PER_REQUEST} session dies with the request's {@link McpToolScope}, an
 * {@link McpSessionType#AUTO} session is closed by the scope once it proves unreachable, and a
 * {@link McpSessionType#REUSE} session is never closed by the scope — its provider owns it
 * until it closes it manually.</p>
 */
public interface McpSession extends AutoCloseable {

    /** Server name as declared by the request configuration. */
    String name();

    /** The lifetime contract of this session, {@link McpSessionType#PER_REQUEST} by default. */
    default McpSessionType type() {
        return McpSessionType.PER_REQUEST;
    }

    /**
     * The description the server published while initializing — the MCP {@code instructions}
     * field — or an empty string when it published none. Defaulted so that a session which
     * carries no description needs no boilerplate.
     */
    default String description() {
        return "";
    }

    /** Tools discovered on this server, already mapped to the harness tool model. */
    List<ToolDefinition<? extends ToolExecutor>> tools();

    /**
     * Probes the connection without listing tools. Like {@link #tools()}, an implementation
     * reports an unreachable or broken server by throwing an unchecked exception; a session
     * that cannot probe its health should stay silent instead of failing the probe.
     *
     * <p>Meant for providers that pre-validate a connection before handing it to a scope — the
     * scope itself judges liveness by whether {@link #tools()} succeeds, and never calls this.</p>
     */
    default void checkHealth() {
    }

    @Override
    void close();

    /**
     * Whether the owning {@link McpToolScope} must close this session when the request ends.
     * Only request-owned sessions answer {@code true}; shared sessions are released by their
     * provider, or by {@link McpToolScope#of} when they prove unreachable.
     */
    default boolean requireAutoClose() {
        return type() == McpSessionType.PER_REQUEST;
    }
}
