package mcp.transport;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.summit.core.conf.McpConfig;
import com.summit.core.conf.McpTransport;
import io.modelcontextprotocol.client.transport.HttpClientStreamableHttpTransport;
import io.modelcontextprotocol.spec.McpClientTransport;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The URL split is the whole point of this builder.
 *
 * <p>The SDK resolves its relative endpoint against the base URI with {@link java.net.URI#resolve},
 * and an endpoint starting with {@code /} discards the base path. A configured
 * {@code https://host/api/v1/mcp} would therefore silently become {@code https://host/mcp}. These
 * assertions pin the split, and {@code McpHttpEndToEndTest} pins the resulting request path against a
 * real server — a root-path test alone would not have caught the bug.</p>
 */
class McpHttpTransportBuilderTest {

    private static final Duration INIT = Duration.ofSeconds(3);
    private static final Duration EXEC = Duration.ofSeconds(4);

    private final ObjectMapper json = new ObjectMapper();
    private final McpHttpTransportBuilder builder = new McpHttpTransportBuilder(json);

    @Test
    void aNonRootPathSurvivesTheSplit() {
        String url = "https://host.example/api/v1/mcp?x=1";

        assertEquals("https://host.example", McpHttpTransportBuilder.resolveBaseUri(url));
        assertEquals("/api/v1/mcp?x=1", McpHttpTransportBuilder.resolveEndpoint(url));
    }

    @Test
    void thePortStaysInTheBaseUri() {
        String url = "http://127.0.0.1:8123/sse/stream";

        assertEquals("http://127.0.0.1:8123", McpHttpTransportBuilder.resolveBaseUri(url));
        assertEquals("/sse/stream", McpHttpTransportBuilder.resolveEndpoint(url));
    }

    @Test
    void aUrlWithoutAPathGetsTheRootPath() {
        assertEquals("https://host.example", McpHttpTransportBuilder.resolveBaseUri("https://host.example"));
        assertEquals("/", McpHttpTransportBuilder.resolveEndpoint("https://host.example"));
    }

    @Test
    void credentialsInTheUrlAreRejectedRatherThanIgnored() {
        assertThrows(IllegalArgumentException.class,
                () -> McpHttpTransportBuilder.resolveBaseUri("https://user:pass@host.example/mcp"));
        assertThrows(IllegalArgumentException.class,
                () -> McpHttpTransportBuilder.resolveEndpoint("https://host.example/mcp#fragment"));
        assertThrows(IllegalArgumentException.class,
                () -> McpHttpTransportBuilder.resolveBaseUri("not-a-url"));
        assertThrows(IllegalArgumentException.class,
                () -> McpHttpTransportBuilder.resolveBaseUri("   "));
    }

    @Test
    void supportsOnlyTheStreamableHttpSettings() {
        assertTrue(builder.supports(http("https://host.example/mcp", null).conf()));
        assertFalse(builder.supports(new McpConfig.Stdio(List.of("npx"), Map.of(), null, INIT, EXEC)));
        assertFalse(builder.supports(new McpConfig.Sse("https://host.example/sse", Map.of(), INIT, EXEC)));
    }

    /** The headers reach the transport; a real server request is checked end to end. */
    @Test
    void configuredHeadersDoNotBreakTheBuild() {
        McpClientTransport transport = builder.build(
                http("https://host.example/mcp", Map.of("Authorization", "Bearer t", "X-Trace", "1")), null);

        assertInstanceOf(HttpClientStreamableHttpTransport.class, transport);
    }

    /** A pinned version is a one-element negotiation list, so the server must answer with it. */
    @Test
    void aPinnedProtocolVersionBecomesASingleSupportedVersion() {
        HttpClientStreamableHttpTransport transport = (HttpClientStreamableHttpTransport) builder.build(
                new McpConfig.MCP("pinned", null, McpTransport.STREAMABLE_HTTP,
                        new McpConfig.StreamableHttp("https://host.example/mcp", Map.of(), INIT, EXEC,
                                " 2025-11-25 ", null, null, null), 100), null);

        assertEquals(List.of("2025-11-25"), transport.protocolVersions());
    }

    /** Without a pin the transport keeps the SDK negotiation list untouched. */
    @Test
    void anAbsentProtocolVersionKeepsTheTransportDefault() {
        HttpClientStreamableHttpTransport transport = (HttpClientStreamableHttpTransport) builder.build(
                http("https://host.example/mcp", null), null);

        assertTrue(transport.protocolVersions().size() > 1,
                "expected the SDK negotiation list, got " + transport.protocolVersions());
    }

    @Test
    void stdioSettingsAreRejectedInsteadOfSilentlyIgnored() {
        McpConfig.MCP stdio = new McpConfig.MCP("stdio", null, McpTransport.STDIO,
                new McpConfig.Stdio(List.of("npx"), Map.of(), null, INIT, EXEC), 100);

        assertThrows(IllegalArgumentException.class, () -> builder.build(stdio, null));
    }

    private static McpConfig.MCP http(String url, Map<String, String> headers) {
        return new McpConfig.MCP("http", null, McpTransport.STREAMABLE_HTTP,
                new McpConfig.StreamableHttp(url, headers, INIT, EXEC), 100);
    }
}
