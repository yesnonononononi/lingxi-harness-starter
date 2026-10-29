package com.summit.adapter.langchain4j.mcp;

import com.summit.core.conf.McpConfig;
import com.summit.core.conf.McpTransport;

import java.net.URI;
import java.time.Duration;
import java.util.regex.Pattern;

/**
 * Validates one MCP server entry before a client is built. The {@code conf} type must match the
 * declared {@link McpTransport}; field rules are per transport.
 */
public class McpValidator {

    /** Shape of an explicitly pinned MCP protocol version, e.g. {@code 2025-11-25}. */
    private static final Pattern PROTOCOL_VERSION = Pattern.compile("\\d{4}-\\d{2}-\\d{2}");


    public static void validate(McpConfig.MCP server) {
        String key = server.name();
        if (key == null || key.isEmpty()) throw invalid(key, "invalid server key");
        if (server.transport() == null) throw invalid(key, "missing transport");
        if (server.conf() == null) throw invalid(key, "missing connection settings");
        if (server.maxOutput() <= 0) throw invalid(key, "max-output must be positive");

        switch (server.conf()) {
            case McpConfig.StreamableHttp http -> {
                requireTransport(key, server, McpTransport.STREAMABLE_HTTP);
                validateHttp(key, http);
            }
            case McpConfig.Sse ignored ->
                    throw invalid(key, "SSE transport is not supported; use streamable-http");
            case McpConfig.Stdio stdio -> {
                requireTransport(key, server, McpTransport.STDIO);
                validateStdio(key, stdio);
            }
        }
    }

    private static void requireTransport(String key, McpConfig.MCP server, McpTransport expected) {
        if (server.transport() != expected) {
            throw invalid(key, "transport " + server.transport() + " does not match the declared settings");
        }
    }

    private static void validateHttp(String key, McpConfig.StreamableHttp conf) {
        URI uri;
        try {
            uri = URI.create(conf.url());
        } catch (Exception e) {
            throw invalid(key, "url must be an absolute HTTP(S) URL");
        }
        if (!("http".equalsIgnoreCase(uri.getScheme()) || "https".equalsIgnoreCase(uri.getScheme()))
                || uri.getHost() == null || uri.getUserInfo() != null || uri.getFragment() != null) {
            throw invalid(key, "url must be an absolute HTTP(S) URL without user info or fragment");
        }
        validateTimeouts(key, conf.initializationTimeout(), conf.executionTimeout());
        validateTuning(key, conf.protocolVersion(), conf.healthCheckInterval(), conf.reconnectInterval());
    }

    private static void validateStdio(String key, McpConfig.Stdio conf) {
        if (conf.command() == null || conf.command().isEmpty()
                || conf.command().stream().anyMatch(part -> part == null || part.isBlank())) {
            throw invalid(key, "command must list the executable and its arguments, e.g. [npx, shadcn@latest, mcp]");
        }
        if (conf.workDir() != null && conf.workDir().isBlank()) {
            throw invalid(key, "work-dir must not be blank when set");
        }
        validateTimeouts(key, conf.initializationTimeout(), conf.executionTimeout());
        validateTuning(key, conf.protocolVersion(), conf.healthCheckInterval(), conf.reconnectInterval());
    }

    /**
     * Guards the optional client-tuning fields. All three are nullable, and {@code null} means
     * "keep the client library default", so only explicitly provided values are range-checked.
     *
     * @param protocolVersion     explicit protocol version, or null to keep the library probe
     * @param healthCheckInterval health check interval, or null to keep the library default
     * @param reconnectInterval   reconnect interval, or null to keep the library default
     */
    private static void validateTuning(String key, String protocolVersion, Duration healthCheckInterval,
                                       Duration reconnectInterval) {
        if (protocolVersion != null) {
            String version = protocolVersion.trim();
            if (version.isEmpty() || version.length() > 32 || !PROTOCOL_VERSION.matcher(version).matches()) {
                throw invalid(key, "protocol-version must look like 2025-11-25");
            }
        }
        if (healthCheckInterval != null && !positive(healthCheckInterval)) {
            throw invalid(key, "health-check-interval must be at least 1ms");
        }
        if (reconnectInterval != null && !positive(reconnectInterval)) {
            throw invalid(key, "reconnect-interval must be at least 1ms");
        }
    }

    private static void validateTimeouts(String key, Duration initialization, Duration execution) {
        if (!positive(initialization) || !positive(execution)) {
            throw invalid(key, "timeouts must be at least 1ms");
        }
    }

    private static boolean positive(Duration duration) {
        return duration != null && duration.compareTo(Duration.ofMillis(1)) >= 0;
    }

    private static IllegalArgumentException invalid(String key, String reason) {
        return new IllegalArgumentException("Invalid MCP server '" + key + "': " + reason);
    }
}
