package mcp.transport;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.summit.core.conf.McpConfig;
import com.summit.core.runtime.workspace.Workspace;
import io.modelcontextprotocol.client.transport.HttpClientStreamableHttpTransport;
import io.modelcontextprotocol.json.McpJsonMapper;
import io.modelcontextprotocol.spec.McpClientTransport;
import mcp.McpClientTransportBuilder;
import mcp.json.Jackson2McpJsonMapper;

import java.net.URI;
import java.net.http.HttpRequest;
import java.util.List;
import java.util.Map;

/**
 * Builds the {@code streamable-http} transport.
 *
 * <h2>Why the URL is split by hand</h2>
 *
 * <p>The SDK joins its base URI with a relative endpoint through {@link URI#resolve}, and its own
 * default endpoint is the absolute path {@code /mcp}. An absolute path reference discards the path
 * of the base URI, so a configured {@code https://host/api/v1/mcp} would silently become
 * {@code https://host/mcp} — the prefix would vanish and the request would reach a different
 * endpoint than the operator configured. The fix is to hand the transport the authority as the base
 * URI and the full configured path (plus query) as the endpoint, which makes the configured URL take
 * effect character for character.</p>
 */
public final class McpHttpTransportBuilder implements McpClientTransportBuilder {

    /** Path used when the configured URL carries no path of its own. */
    private static final String ROOT_PATH = "/";

    private final McpJsonMapper jsonMapper;

    /**
     * @param objectMapper application mapper used for the JSON-RPC framing; the SDK's ServiceLoader
     *                     lookup is deliberately bypassed
     */
    public McpHttpTransportBuilder(ObjectMapper objectMapper) {
        this.jsonMapper = new Jackson2McpJsonMapper(objectMapper);
    }

    @Override
    public boolean supports(McpConfig.Conf conf) {
        return conf instanceof McpConfig.StreamableHttp;
    }

    @Override
    public McpClientTransport build(McpConfig.MCP server, Workspace workspace) {
        if (server == null || !(server.conf() instanceof McpConfig.StreamableHttp http)) {
            throw new IllegalArgumentException(
                    "MCP server '" + (server == null ? "null" : server.name())
                            + "': streamable-http transport requires streamable-http connection settings");
        }
        String url = http.url();
        HttpClientStreamableHttpTransport.Builder builder = HttpClientStreamableHttpTransport
                .builder(resolveBaseUri(url))
                .endpoint(resolveEndpoint(url))
                .jsonMapper(jsonMapper)
                .requestBuilder(requestBuilderOf(http.headers()));
        String protocolVersion = http.protocolVersion() == null ? "" : http.protocolVersion().trim();
        if (!protocolVersion.isEmpty()) {
            // A single supported version is a pin: the server must answer with exactly this one.
            builder.supportedProtocolVersions(List.of(protocolVersion));
        }
        return builder.build();
    }

    /**
     * @param url configured endpoint URL
     * @return the {@code scheme://authority} part, i.e. what the SDK base URI may carry without
     *         interfering with the configured path
     */
    static String resolveBaseUri(String url) {
        URI uri = requireAbsolute(url);
        String authority = uri.getRawAuthority();
        if (authority == null || authority.isEmpty()) {
            throw new IllegalArgumentException("Invalid MCP server URL: missing authority");
        }
        return uri.getScheme() + "://" + authority;
    }

    /**
     * @param url configured endpoint URL
     * @return the configured path with any trailing query, i.e. the endpoint passed to the SDK
     */
    static String resolveEndpoint(String url) {
        URI uri = requireAbsolute(url);
        String path = uri.getRawPath();
        String endpoint = path == null || path.isEmpty() ? ROOT_PATH : path;
        String query = uri.getRawQuery();
        return query == null || query.isEmpty() ? endpoint : endpoint + "?" + query;
    }

    private static URI requireAbsolute(String url) {
        if (url == null || url.isBlank()) {
            throw new IllegalArgumentException("Invalid MCP server URL: must not be blank");
        }
        URI uri;
        try {
            uri = URI.create(url.trim());
        } catch (RuntimeException failure) {
            throw new IllegalArgumentException("Invalid MCP server URL: " + failure.getMessage(), failure);
        }
        if (!uri.isAbsolute() || uri.getHost() == null) {
            throw new IllegalArgumentException("Invalid MCP server URL: must be an absolute HTTP(S) URL");
        }
        if (uri.getUserInfo() != null || uri.getFragment() != null) {
            throw new IllegalArgumentException(
                    "Invalid MCP server URL: user info and fragments are not allowed");
        }
        return uri;
    }

    /**
     * Turns the configured headers into the request template every MCP request is copied from.
     * Headers carry the credentials, so nothing here is ever logged.
     *
     * @param headers configured header values keyed by name, may be null
     * @return the request builder the transport copies for each call
     */
    private static HttpRequest.Builder requestBuilderOf(Map<String, String> headers) {
        HttpRequest.Builder requestBuilder = HttpRequest.newBuilder();
        if (headers == null || headers.isEmpty()) {
            return requestBuilder;
        }
        for (Map.Entry<String, String> header : headers.entrySet()) {
            if (header.getKey() != null && !header.getKey().isBlank() && header.getValue() != null) {
                requestBuilder.setHeader(header.getKey().trim(), header.getValue());
            }
        }
        return requestBuilder;
    }
}
