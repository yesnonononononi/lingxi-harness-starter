package com.summit.adapter.langchain4j.mcp;

import com.summit.core.conf.McpConfig;
import com.summit.core.conf.McpTransport;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class McpValidatorTest {

    private static final Duration INIT = Duration.ofSeconds(3);
    private static final Duration EXEC = Duration.ofSeconds(4);

    @Test
    void streamableHttpWithValidUrlPasses() {
        McpConfig.MCP server = http("github", "https://github.example/mcp");
        assertDoesNotThrow(() -> McpValidator.validate(server));
    }

    @Test
    void stdioWithArgvCommandPasses() {
        McpConfig.MCP server = new McpConfig.MCP("shadcn", "shadcn component registry", McpTransport.STDIO,
                new McpConfig.Stdio(List.of("npx", "shadcn@latest", "mcp"),
                        Map.of("GITHUB_TOKEN", "ghp_x"), INIT, EXEC),
                100);
        assertDoesNotThrow(() -> McpValidator.validate(server));
    }

    @Test
    void stdioWithoutCommandIsRejected() {
        McpConfig.MCP server = new McpConfig.MCP("shadcn", null, McpTransport.STDIO,
                new McpConfig.Stdio(List.of(), null, INIT, EXEC), 100);
        assertInvalid(server, "command");
    }

    @Test
    void stdioWithBlankArgumentIsRejected() {
        McpConfig.MCP server = new McpConfig.MCP("shadcn", null, McpTransport.STDIO,
                new McpConfig.Stdio(java.util.Arrays.asList("npx", " "), null, INIT, EXEC), 100);
        assertInvalid(server, "command");
    }

    @Test
    void sseIsRejectedBecauseTheClientLibraryHasNoSseTransport() {
        McpConfig.MCP server = new McpConfig.MCP("legacy", null, McpTransport.SSE,
                new McpConfig.Sse("https://legacy.example/sse", Map.of(), INIT, EXEC), 100);
        assertInvalid(server, "SSE transport is not supported");
    }

    @Test
    void confTypeMustMatchTheDeclaredTransport() {
        McpConfig.MCP server = new McpConfig.MCP("shadcn", null, McpTransport.STDIO,
                new McpConfig.StreamableHttp("https://shadcn.example/mcp", Map.of(), INIT, EXEC),
                100);
        assertInvalid(server, "does not match");
    }

    @Test
    void httpUrlMustBeAbsoluteHttpsWithoutUserInfoOrFragment() {
        assertInvalid(http("a", "not-a-url"), "url");
        assertInvalid(http("b", "ftp://b.example/mcp"), "url");
        assertInvalid(http("c", "https://user:pass@c.example/mcp"), "url");
        assertInvalid(http("d", "https://d.example/mcp#frag"), "url");
    }

    @Test
    void serverKeyAndMaxOutputKeepTheirGuards() {
        assertInvalid(http("", "https://a.example/mcp"), "invalid server key");
        assertInvalid(new McpConfig.MCP("a", null, McpTransport.STREAMABLE_HTTP,
                new McpConfig.StreamableHttp("https://a.example/mcp", Map.of(), INIT, EXEC),
                0), "max-output");
    }

    /**
     * A server name is a label, never part of a model-facing tool name: the tools reach the model as
     * {@code mcp_} plus the names the server itself published, so dots and spaces in the name are
     * harmless instead of poisoning every function name of that server.
     */
    @Test
    void serverNameIsNeverUsedAsAToolNamePrefix() {
        assertDoesNotThrow(() -> McpValidator.validate(http("draw.io", "https://mcp.draw.io/mcp")));
        assertDoesNotThrow(() -> McpValidator.validate(http("bad name", "https://a.example/mcp")));
    }

    @Test
    void descriptionIsOptional() {
        assertDoesNotThrow(() -> McpValidator.validate(new McpConfig.MCP("a", null, McpTransport.STREAMABLE_HTTP,
                new McpConfig.StreamableHttp("https://a.example/mcp", Map.of(), INIT, EXEC), 100)));
    }

    @Test
    void timeoutsMustBePositive() {
        assertInvalid(new McpConfig.MCP("a", null, McpTransport.STREAMABLE_HTTP,
                new McpConfig.StreamableHttp("https://a.example/mcp", Map.of(), null, EXEC),
                100), "timeouts");
        assertInvalid(new McpConfig.MCP("s", null, McpTransport.STDIO,
                new McpConfig.Stdio(List.of("npx"), Map.of(), INIT, Duration.ZERO),
                100), "timeouts");
    }

    @Test
    void stdioWorkDirIsOptionalButNeverBlank() {
        assertDoesNotThrow(() -> McpValidator.validate(new McpConfig.MCP("npx", null, McpTransport.STDIO,
                new McpConfig.Stdio(List.of("npx"), Map.of(), "D:/tools", INIT, EXEC), 100)));
        assertInvalid(new McpConfig.MCP("npx", null, McpTransport.STDIO,
                new McpConfig.Stdio(List.of("npx"), Map.of(), "  ", INIT, EXEC), 100), "work-dir");
    }

    @Test
    void parseMapsTheDashedConfigForm() {
        assertEquals(McpTransport.STREAMABLE_HTTP, McpTransport.parse("streamable-http"));
        assertEquals(McpTransport.STDIO, McpTransport.parse("stdio"));
        assertEquals(McpTransport.SSE, McpTransport.parse(" sse "));
        assertThrows(IllegalArgumentException.class, () -> McpTransport.parse("carrier-pigeon"));
        assertThrows(IllegalArgumentException.class, () -> McpTransport.parse(null));
    }

    @Test
    void validClientTuningPasses() {
        assertDoesNotThrow(() -> McpValidator.validate(httpTuned("github", "2025-11-25", true,
                Duration.ofSeconds(30), Duration.ofSeconds(5))));
        assertDoesNotThrow(() -> McpValidator.validate(new McpConfig.MCP("npx", null, McpTransport.STDIO,
                new McpConfig.Stdio(List.of("npx"), Map.of(), null, INIT, EXEC,
                        "2025-11-25", false, Duration.ofMillis(1), Duration.ofMillis(1)), 100)));
    }

    /** A pinned protocol version is what removes the failing version probe, so its shape is guarded. */
    @Test
    void protocolVersionMustLookLikeAYyyyMmDdDate() {
        assertInvalid(httpTuned("a", "abc", null, null, null), "protocol-version");
        assertInvalid(httpTuned("b", "   ", null, null, null), "protocol-version");
        assertInvalid(httpTuned("c", "2025-11-25T00:00:00Z-extended-suffix", null, null, null),
                "protocol-version");
        assertInvalid(new McpConfig.MCP("npx", null, McpTransport.STDIO,
                new McpConfig.Stdio(List.of("npx"), Map.of(), null, INIT, EXEC,
                        "latest", null, null, null), 100), "protocol-version");
    }

    /** Intervals are matched to the library's own granularity: nothing below one millisecond. */
    @Test
    void tuningIntervalsMustBeAtLeastOneMillisecond() {
        assertInvalid(httpTuned("a", null, null, Duration.ZERO, null), "health-check-interval");
        assertInvalid(httpTuned("b", null, null, Duration.ofMillis(-1), null), "health-check-interval");
        assertInvalid(httpTuned("c", null, null, null, Duration.ZERO), "reconnect-interval");
        assertInvalid(new McpConfig.MCP("npx", null, McpTransport.STDIO,
                new McpConfig.Stdio(List.of("npx"), Map.of(), null, INIT, EXEC,
                        null, null, Duration.ZERO, null), 100), "health-check-interval");
    }

    /** Every tuning field is optional; leaving all four unset must stay valid for both transports. */
    @Test
    void absentClientTuningIsValid() {
        assertDoesNotThrow(() -> McpValidator.validate(httpTuned("a", null, null, null, null)));
        assertDoesNotThrow(() -> McpValidator.validate(new McpConfig.MCP("npx", null, McpTransport.STDIO,
                new McpConfig.Stdio(List.of("npx"), Map.of(), null, INIT, EXEC, null, null, null, null),
                100)));
    }

    private static McpConfig.MCP http(String name, String url) {
        return new McpConfig.MCP(name, null, McpTransport.STREAMABLE_HTTP,
                new McpConfig.StreamableHttp(url, Map.of(), INIT, EXEC), 100);
    }

    private static McpConfig.MCP httpTuned(String name, String protocolVersion, Boolean autoHealthCheck,
                                           Duration healthCheckInterval, Duration reconnectInterval) {
        return new McpConfig.MCP(name, null, McpTransport.STREAMABLE_HTTP,
                new McpConfig.StreamableHttp("https://" + (name.isEmpty() ? "a" : name) + ".example/mcp",
                        Map.of(), INIT, EXEC, protocolVersion, autoHealthCheck, healthCheckInterval,
                        reconnectInterval), 100);
    }

    private static void assertInvalid(McpConfig.MCP server, String expectedFragment) {
        IllegalArgumentException error = assertThrows(IllegalArgumentException.class,
                () -> McpValidator.validate(server));
        assertTrue(error.getMessage().contains(expectedFragment),
                "expected '" + expectedFragment + "' in: " + error.getMessage());
    }
}
