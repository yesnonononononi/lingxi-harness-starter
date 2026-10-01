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

    /** An HTTP(S) endpoint reached with the streamable-http client transport. */
    public record StreamableHttp(
            String url,
            Map<String, String> headers,
            Duration initializationTimeout,
            Duration executionTimeout
    ) implements Conf {}

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
     * process environment.
     */
    public record Stdio(
            List<String> command,
            Map<String, String> env,
            Duration initializationTimeout,
            Duration executionTimeout
    ) implements Conf {}

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
