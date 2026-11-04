package mcp;

import com.summit.core.conf.McpConfig;
import com.summit.core.runtime.workspace.Workspace;
import io.modelcontextprotocol.spec.McpClientTransport;

/**
 * Creates the client transport of one MCP server.
 *
 * <p>A transport builder is chosen by the connection settings of the server, not by the declared
 * {@code transport} name: the two are validated to agree before any builder is consulted, so an
 * inconsistent entry fails with a message about the entry instead of about a missing transport.</p>
 *
 * <p>Applications may replace the builders — for example to add a proxy or an alternative HTTP
 * client — by publishing their own instances as beans; the factory keeps no knowledge of the
 * concrete transports.</p>
 */
public interface McpClientTransportBuilder {

    /**
     * Whether this builder can build the transport of the given connection settings.
     *
     * @param conf connection settings of a server, never null in practice
     * @return {@code true} when {@link #build} accepts the same settings
     */
    boolean supports(McpConfig.Conf conf);

    /**
     * Builds the transport of one server.
     *
     * <p>The returned transport is not connected yet: the caller registers the inbound handler and
     * completes protocol initialization. Ownership transfers to the caller, which must close it —
     * directly or through the client that wraps it.</p>
     *
     * @param server    server being opened; carries the timeouts and the connection settings
     * @param workspace workspace of the request, or null when the request declares none
     * @return a transport that is ready to be connected
     */
    McpClientTransport build(McpConfig.MCP server, Workspace workspace);
}
