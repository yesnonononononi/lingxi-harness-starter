package mcp;

import com.summit.core.conf.McpConfig;
import com.summit.core.tool.ConcurrentPolicy;

/**
 * How the tools of one server are published to the model.
 *
 * <p>The prefix is the only part that differs between callers — the framework publishes
 * {@code mcp_} plus the server's own tool name, while an application may embed its server name to
 * keep tools of different servers apart. Everything else is derived from the server configuration
 * here, once, so that neither caller can drift: the output limit comes from
 * {@link McpConfig.MCP#maxOutput()}, and the runtime timeout is the execution timeout rounded up to
 * whole seconds plus a grace period, which lets a slow server report its own error before the local
 * guard fires.</p>
 *
 * <p>Every MCP tool mutates remote state that this process cannot see, so the concurrency policy is
 * {@link ConcurrentPolicy#SERIAL_MUTATION}: a batch never runs two MCP tools side by side.</p>
 *
 * @param maxOutput              output budget of one tool result, in characters
 * @param runtimeTimeoutSeconds  runtime timeout of one tool call, in seconds
 * @param namePrefix             prefix of the published tool name; never null
 * @param concurrentPolicy       concurrency policy of the published tools; never null
 */
public record McpToolOptions(int maxOutput, long runtimeTimeoutSeconds, String namePrefix,
                             ConcurrentPolicy concurrentPolicy) {

    /** Slack added to the execution timeout so the server's own error wins the race. */
    private static final long RUNTIME_TIMEOUT_GRACE_SECONDS = 5L;

    /** Normalizes the prefix and the policy so no call site has to null-check them. */
    public McpToolOptions {
        namePrefix = namePrefix == null ? "" : namePrefix;
        runtimeTimeoutSeconds = Math.max(1L, runtimeTimeoutSeconds);
        concurrentPolicy = concurrentPolicy == null ? ConcurrentPolicy.SERIAL_MUTATION : concurrentPolicy;
    }

    /**
     * Derives the publication parameters of one server.
     *
     * @param namePrefix prefix of the published tool names; blank or null means unprefixed
     * @param server     server being published; supplies the output limit and the execution timeout
     * @return the parameters of every tool of that server
     * @throws IllegalArgumentException when the server is missing or declares no execution timeout
     */
    public static McpToolOptions withPrefix(String namePrefix, McpConfig.MCP server) {
        if (server == null) {
            throw new IllegalArgumentException("MCP server must not be null");
        }
        if (server.executionTimeout() == null) {
            throw new IllegalArgumentException(
                    "MCP server '" + server.name() + "': execution timeout is missing");
        }
        long runtimeTimeout = (long) Math.ceil(server.executionTimeout().toMillis() / 1000.0)
                + RUNTIME_TIMEOUT_GRACE_SECONDS;
        return new McpToolOptions(server.maxOutput(), runtimeTimeout, namePrefix, ConcurrentPolicy.SERIAL_MUTATION);
    }
}
