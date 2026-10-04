package com.summit.core.mcp;

/**
 * The lifetime contract of an {@link McpSession}: who closes the underlying connection, and when.
 */
public enum McpSessionType {

    /**
     * Owned by one request: the request's {@link McpToolScope} closes the session as soon as the
     * execution reaches a terminal state.
     */
    PER_REQUEST,

    /**
     * Shared across requests, and closed by the scope only once the session proves unreachable
     * during adoption, so its provider can rebuild it on a later request.
     */
    AUTO,

    /**
     * Shared across requests and never closed by the scope — its provider keeps the connection
     * until it closes it manually.
     */
    REUSE
}
