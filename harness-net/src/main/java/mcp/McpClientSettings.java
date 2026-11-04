package mcp;

import com.summit.core.conf.McpConfig;

import java.time.Duration;

/**
 * Client tuning of the MCP connection layer: what the SDK client and the health-check machinery
 * use when the server configuration leaves a field unset.
 *
 * <p>The defaults mirror the retired client library — 30s to initialize, 60s per request, health
 * checking on with a 30s interval, a 5s floor between reconnect attempts — so a stored
 * configuration keeps behaving exactly as it did. The one deliberate tightening is
 * {@link #reconnectAttempts()}: the retired library retried forever, this implementation gives up
 * after three attempts and marks the connection unavailable, leaving the tools published but
 * failing (rebuilding is the owner's decision, not a background task's).</p>
 *
 * <p>The backoff parameters mirror the LingXi {@code RetryUtil}: {@code base * multiple^n} capped,
 * then a uniform jitter between zero and the capped value.</p>
 *
 * @param clientName                  client name announced during initialize
 * @param clientVersion               client version announced during initialize
 * @param defaultInitializationTimeout initialization timeout used when the server declares none
 * @param defaultRequestTimeout       per-request timeout used when the server declares none
 * @param defaultAutoHealthCheck      whether health checking is on when the server does not say
 * @param defaultHealthCheckInterval  health-check interval used when the server declares none
 * @param defaultReconnectInterval    floor between two reconnect attempts
 * @param reconnectAttempts           upper bound of consecutive reconnect attempts
 * @param backoffBaseMillis           first backoff step in milliseconds
 * @param backoffMultiple             growth factor of the backoff
 * @param backoffCapMillis            upper bound of the backoff
 */
public record McpClientSettings(
        String clientName,
        String clientVersion,
        Duration defaultInitializationTimeout,
        Duration defaultRequestTimeout,
        boolean defaultAutoHealthCheck,
        Duration defaultHealthCheckInterval,
        Duration defaultReconnectInterval,
        int reconnectAttempts,
        long backoffBaseMillis,
        int backoffMultiple,
        long backoffCapMillis) {

    /** Client name announced during initialize; the protocol-visible identity of this harness. */
    public static final String DEFAULT_CLIENT_NAME = "lingxi-harness";

    /** Version announced during initialize; bumped together with the framework release. */
    public static final String DEFAULT_CLIENT_VERSION = "1.1.8";

    private static final Duration DEFAULT_INITIALIZATION_TIMEOUT = Duration.ofSeconds(30);
    private static final Duration DEFAULT_REQUEST_TIMEOUT = Duration.ofSeconds(60);
    private static final Duration DEFAULT_HEALTH_CHECK_INTERVAL = Duration.ofSeconds(30);
    private static final Duration DEFAULT_RECONNECT_INTERVAL = Duration.ofSeconds(5);
    private static final int DEFAULT_RECONNECT_ATTEMPTS = 3;
    private static final long DEFAULT_BACKOFF_BASE_MILLIS = 2_000L;
    private static final int DEFAULT_BACKOFF_MULTIPLE = 2;
    private static final long DEFAULT_BACKOFF_CAP_MILLIS = 60_000L;

    /** Normalizes a hand-built instance so that no method below has to re-check its inputs. */
    public McpClientSettings {
        clientName = hasText(clientName) ? clientName.trim() : DEFAULT_CLIENT_NAME;
        clientVersion = hasText(clientVersion) ? clientVersion.trim() : DEFAULT_CLIENT_VERSION;
        defaultInitializationTimeout = positiveOr(defaultInitializationTimeout, DEFAULT_INITIALIZATION_TIMEOUT);
        defaultRequestTimeout = positiveOr(defaultRequestTimeout, DEFAULT_REQUEST_TIMEOUT);
        defaultHealthCheckInterval = positiveOr(defaultHealthCheckInterval, DEFAULT_HEALTH_CHECK_INTERVAL);
        defaultReconnectInterval = positiveOr(defaultReconnectInterval, DEFAULT_RECONNECT_INTERVAL);
        reconnectAttempts = Math.max(1, reconnectAttempts);
        backoffBaseMillis = Math.max(0L, backoffBaseMillis);
        backoffMultiple = Math.max(1, backoffMultiple);
        backoffCapMillis = Math.max(0L, backoffCapMillis);
    }

    /** The production defaults described in the class comment. */
    public static McpClientSettings defaults() {
        return new McpClientSettings(DEFAULT_CLIENT_NAME, DEFAULT_CLIENT_VERSION,
                DEFAULT_INITIALIZATION_TIMEOUT, DEFAULT_REQUEST_TIMEOUT, true,
                DEFAULT_HEALTH_CHECK_INTERVAL, DEFAULT_RECONNECT_INTERVAL, DEFAULT_RECONNECT_ATTEMPTS,
                DEFAULT_BACKOFF_BASE_MILLIS, DEFAULT_BACKOFF_MULTIPLE, DEFAULT_BACKOFF_CAP_MILLIS);
    }

    /**
     * Initialization timeout of one server: the configured value wins, the default applies only
     * when the configuration carries none.
     *
     * @param server server being opened
     * @return the effective initialization timeout, never null
     */
    public Duration resolveInitializationTimeout(McpConfig.MCP server) {
        Duration configured = server == null ? null : server.initializationTimeout();
        return positiveOr(configured, defaultInitializationTimeout);
    }

    /**
     * Per-request timeout of one server, i.e. the bound of every tool call and of {@code listTools}.
     *
     * @param server server being opened
     * @return the effective request timeout, never null
     */
    public Duration resolveRequestTimeout(McpConfig.MCP server) {
        Duration configured = server == null ? null : server.executionTimeout();
        return positiveOr(configured, defaultRequestTimeout);
    }

    /**
     * Whether the connection of one server is health-checked in the background.
     *
     * @param conf connection settings of the server, may be null
     * @return {@code true} when no explicit value was configured and the default is enabled
     */
    public boolean resolveAutoHealthCheck(McpConfig.Conf conf) {
        Boolean configured = switch (conf) {
            case McpConfig.StreamableHttp http -> http.autoHealthCheck();
            case McpConfig.Sse ignored -> null;
            case McpConfig.Stdio stdio -> stdio.autoHealthCheck();
            case null -> null;
        };
        return configured == null ? defaultAutoHealthCheck : configured;
    }

    /**
     * Interval between two health checks of one server.
     *
     * @param conf connection settings of the server, may be null
     * @return the effective interval, never null
     */
    public Duration resolveHealthCheckInterval(McpConfig.Conf conf) {
        Duration configured = switch (conf) {
            case McpConfig.StreamableHttp http -> http.healthCheckInterval();
            case McpConfig.Sse ignored -> null;
            case McpConfig.Stdio stdio -> stdio.healthCheckInterval();
            case null -> null;
        };
        return positiveOr(configured, defaultHealthCheckInterval);
    }

    /**
     * Floor between two reconnect attempts of one server.
     *
     * @param conf connection settings of the server, may be null
     * @return the effective floor, never null
     */
    public Duration resolveReconnectInterval(McpConfig.Conf conf) {
        Duration configured = switch (conf) {
            case McpConfig.StreamableHttp http -> http.reconnectInterval();
            case McpConfig.Sse ignored -> null;
            case McpConfig.Stdio stdio -> stdio.reconnectInterval();
            case null -> null;
        };
        return positiveOr(configured, defaultReconnectInterval);
    }

    /**
     * Explicit MCP protocol version of one server, trimmed; empty when the configuration leaves the
     * version to the transport.
     *
     * @param conf connection settings of the server, may be null
     * @return the configured version without padding, or an empty string
     */
    public String resolveProtocolVersion(McpConfig.Conf conf) {
        String configured = switch (conf) {
            case McpConfig.StreamableHttp http -> http.protocolVersion();
            case McpConfig.Sse ignored -> null;
            case McpConfig.Stdio stdio -> stdio.protocolVersion();
            case null -> null;
        };
        return hasText(configured) ? configured.trim() : "";
    }

    private static boolean hasText(String value) {
        return value != null && !value.isBlank();
    }

    private static Duration positiveOr(Duration candidate, Duration fallback) {
        if (candidate == null || candidate.isNegative() || candidate.isZero()) {
            return fallback;
        }
        return candidate;
    }
}
