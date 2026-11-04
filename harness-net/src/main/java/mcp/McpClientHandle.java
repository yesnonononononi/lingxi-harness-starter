package mcp;

import io.modelcontextprotocol.client.McpSyncClient;

/**
 * One open MCP connection.
 *
 * <p>Exists so that {@link McpResilientConnection} can hold "a client plus the knowledge of how to
 * release whatever surrounds it" behind a single seam: an HTTP client needs nothing beyond its own
 * graceful close, while a sandboxed stdio client must release its launch recipe first. Every
 * implementation must close in the right order and be idempotent, because a connection can be
 * closed by its session, by a reconnect swap and by the factory's shutdown.</p>
 */
public interface McpClientHandle {

    /** @return the initialized client of this handle, never null */
    McpSyncClient client();

    /** Releases the connection; calling it twice must be harmless. */
    void close();
}
