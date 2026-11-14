package mcp;

import com.summit.core.conf.McpConfig;

/**
 * Supplies the per-server authorization handling of the {@code streamable-http} transport.
 *
 * <p>The client library exposes two separate hooks — one that customizes every outgoing request and
 * one that decides whether a 401/403 may be replayed — and a host that manages credentials has to
 * own both: the request hook carries the current credential, the error hook renews it. This
 * interface exists so that a host publishes <em>one</em> bean instead of replacing the transport
 * builders, which would also have to reproduce the URL split and the stdio launch.</p>
 *
 * <p>Only {@code streamable-http} consults it. A stdio server has no HTTP authorization surface, so
 * {@link #resolveAuthorization(McpConfig.MCP)} must answer {@code null} for it rather than a no-op
 * implementation: an absent return value keeps the previous wiring untouched.</p>
 */
public interface McpAuthorizationProvider {

    /**
     * Returns the authorization handling of one server.
     *
     * @param server server being opened or reconnected
     * @return the handling of this server, or {@code null} when this provider does not own it —
     *         for example a server configured with a static credential, which must keep travelling
     *         as a plain configured header
     */
    McpServerAuthorization resolveAuthorization(McpConfig.MCP server);
}
