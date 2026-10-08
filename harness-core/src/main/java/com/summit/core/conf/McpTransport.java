package com.summit.core.conf;

import java.util.Locale;

/**
 * Transport of an MCP server. The config-string form is lowercase-dashed ("streamable-http");
 * {@link #parse} maps it here so callers never hand-roll the normalization.
 */
public enum McpTransport {
    STREAMABLE_HTTP,
    SSE,
    STDIO;

    public static McpTransport parse(String value) {
        String normalized = value == null ? "" : value.trim().toUpperCase(Locale.ROOT).replace('-', '_');
        try {
            return McpTransport.valueOf(normalized);
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException("Unknown MCP transport: " + value, e);
        }
    }
}
