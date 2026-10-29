package com.summit.core.conf;

import com.fasterxml.jackson.annotation.JsonSubTypes;
import com.fasterxml.jackson.annotation.JsonTypeInfo;
import lombok.Data;

import java.time.Duration;
import java.util.List;
import java.util.Map;

/**
 * Request-level MCP server configuration. Connection settings live in a per-transport record
 * ({@code conf}), discriminated by {@link MCP#transport()}; validators and factories switch over
 * {@code conf} exhaustively, so adding a transport is a compile error until every site handles it.
 */
@Data
public class McpConfig {

    /** Connection settings of one server, shaped by its transport. */
    @JsonTypeInfo(use = JsonTypeInfo.Id.NAME, property = "type",include = JsonTypeInfo.As.PROPERTY)
    @JsonSubTypes({
            @JsonSubTypes.Type(value = StreamableHttp.class, name = "streamable-http"),
            @JsonSubTypes.Type(value = Sse.class, name = "sse"),
            @JsonSubTypes.Type(value = Stdio.class, name = "stdio")
    })
    public sealed interface Conf permits StreamableHttp, Sse, Stdio {}

    /**
     * An HTTP(S) endpoint reached with the streamable-http client transport.
     *
     * <p>The four client-tuning components are nullable on purpose: {@code null} means "leave the
     * client library default in place", which keeps every stored configuration written before
     * these fields existed behaving exactly as it did. Never substitute a business default here —
     * that would silently change the behaviour of the old entries.
     *
     * @param protocolVersion     explicit MCP protocol version, e.g. {@code 2025-11-25}; when null
     *                            the library keeps probing for a version
     * @param autoHealthCheck     enables the library's background health check; null keeps the
     *                            library default (enabled)
     * @param healthCheckInterval interval between health checks; null keeps the library default
     * @param reconnectInterval   interval between reconnect attempts; null keeps the library default
     */
    public record StreamableHttp(
            String url,
            Map<String, String> headers,
            Duration initializationTimeout,
            Duration executionTimeout,
            String protocolVersion,
            Boolean autoHealthCheck,
            Duration healthCheckInterval,
            Duration reconnectInterval
    ) implements Conf {
        /** Keeps call sites written before the client-tuning fields existed on the same shape. */
        public StreamableHttp(String url, Map<String, String> headers, Duration initializationTimeout,
                              Duration executionTimeout) {
            this(url, headers, initializationTimeout, executionTimeout, null, null, null, null);
        }
    }

    /** An SSE endpoint; the bundled client library has no SSE transport, so this is rejected. */
    public record Sse(
            String url,
            Map<String, String> headers,
            Duration initializationTimeout,
            Duration executionTimeout
    ) implements Conf {}

    /**
     * A local child process reached over stdin/stdout, argv-style — e.g.
     * {@code ["npx", "shadcn@latest", "mcp"]}. {@code env} adds variables on top of the inherited
     * process environment; {@code workDir} is the directory the child starts in (its parent's
     * directory when absent), which decides how it resolves relative paths.
     */
    public record Stdio(
            List<String> command,
            Map<String, String> env,
            String workDir,
            Duration initializationTimeout,
            Duration executionTimeout,
            String protocolVersion,
            Boolean autoHealthCheck,
            Duration healthCheckInterval,
            Duration reconnectInterval
    ) implements Conf {
        /** Keeps call sites written before the working directory existed on the same shape. */
        public Stdio(List<String> command, Map<String, String> env, Duration initializationTimeout,
                     Duration executionTimeout) {
            this(command, env, null, initializationTimeout, executionTimeout, null, null, null, null);
        }

        /** Keeps call sites written before the client-tuning fields existed on the same shape. */
        public Stdio(List<String> command, Map<String, String> env, String workDir,
                     Duration initializationTimeout, Duration executionTimeout) {
            this(command, env, workDir, initializationTimeout, executionTimeout, null, null, null, null);
        }
    }

    public record MCP(
            String name,
            String description,
            McpTransport transport,
            Conf conf,
            int maxOutput
    ) {

        public Duration initializationTimeout() {
            return switch (conf) {
                case StreamableHttp conf_ -> conf_.initializationTimeout();
                case Sse conf_ -> conf_.initializationTimeout();
                case Stdio conf_ -> conf_.initializationTimeout();
            };
        }

        public Duration executionTimeout() {
            return switch (conf) {
                case StreamableHttp conf_ -> conf_.executionTimeout();
                case Sse conf_ -> conf_.executionTimeout();
                case Stdio conf_ -> conf_.executionTimeout();
            };
        }
    }

    private List<MCP> mcp;
}
