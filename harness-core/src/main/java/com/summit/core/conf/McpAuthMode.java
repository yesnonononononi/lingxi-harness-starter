package com.summit.core.conf;

/**
 * How the credential of an MCP server is obtained.
 *
 * <p>The framework never interprets this value: it exists so that a host can tell its own
 * authorization provider that a transport has to be built with the library's credential hooks,
 * without the framework having to know which authorization flow that host implements.</p>
 */
public enum McpAuthMode {

    /** The credential, if any, travels inside the configured headers or environment. */
    NONE,

    /** The credential is obtained at runtime through an authorization flow. */
    OAUTH
}
