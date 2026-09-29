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
        McpConfig.MCP server = new McpConfig.MCP("shadcn", McpTransport.STDIO,
                new McpConfig.Stdio(List.of("npx", "shadcn@latest", "mcp"),
                        Map.of("GITHUB_TOKEN", "ghp_x"), INIT, EXEC),
                null, 100);
        assertDoesNotThrow(() -> McpValidator.validate(server));
    }

    @Test
    void stdioWithoutCommandIsRejected() {
        McpConfig.MCP server = new McpConfig.MCP("shadcn", McpTransport.STDIO,
                new McpConfig.Stdio(List.of(), null, INIT, EXEC), null, 100);
        assertInvalid(server, "command");
    }

    @Test
    void stdioWithBlankArgumentIsRejected() {
        McpConfig.MCP server = new McpConfig.MCP("shadcn", McpTransport.STDIO,
                new McpConfig.Stdio(java.util.Arrays.asList("npx", " "), null, INIT, EXEC), null, 100);
        assertInvalid(server, "command");
    }

    @Test
    void sseIsRejectedBecauseTheClientLibraryHasNoSseTransport() {
        McpConfig.MCP server = new McpConfig.MCP("legacy", McpTransport.SSE,
                new McpConfig.Sse("https://legacy.example/sse", Map.of(), INIT, EXEC), null, 100);
        assertInvalid(server, "SSE transport is not supported");
    }

    @Test
    void confTypeMustMatchTheDeclaredTransport() {
        McpConfig.MCP server = new McpConfig.MCP("shadcn", McpTransport.STDIO,
                new McpConfig.StreamableHttp("https://shadcn.example/mcp", Map.of(), INIT, EXEC),
                null, 100);
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
    void namePrefixAndMaxOutputKeepTheirGuards() {
        assertInvalid(http("bad name", "https://a.example/mcp"), "invalid server key");
        assertInvalid(new McpConfig.MCP("a", McpTransport.STREAMABLE_HTTP,
                new McpConfig.StreamableHttp("https://a.example/mcp", Map.of(), INIT, EXEC),
                "bad prefix!", 100), "invalid tool-name-prefix");
        assertInvalid(new McpConfig.MCP("a", McpTransport.STREAMABLE_HTTP,
                new McpConfig.StreamableHttp("https://a.example/mcp", Map.of(), INIT, EXEC),
                null, 0), "max-output");
    }

    @Test
    void timeoutsMustBePositive() {
        assertInvalid(new McpConfig.MCP("a", McpTransport.STREAMABLE_HTTP,
                new McpConfig.StreamableHttp("https://a.example/mcp", Map.of(), null, EXEC),
                null, 100), "timeouts");
        assertInvalid(new McpConfig.MCP("s", McpTransport.STDIO,
                new McpConfig.Stdio(List.of("npx"), Map.of(), INIT, Duration.ZERO),
                null, 100), "timeouts");
    }

    @Test
    void parseMapsTheDashedConfigForm() {
        assertEquals(McpTransport.STREAMABLE_HTTP, McpTransport.parse("streamable-http"));
        assertEquals(McpTransport.STDIO, McpTransport.parse("stdio"));
        assertEquals(McpTransport.SSE, McpTransport.parse(" sse "));
        assertThrows(IllegalArgumentException.class, () -> McpTransport.parse("carrier-pigeon"));
        assertThrows(IllegalArgumentException.class, () -> McpTransport.parse(null));
    }

    private static McpConfig.MCP http(String name, String url) {
        return new McpConfig.MCP(name, McpTransport.STREAMABLE_HTTP,
                new McpConfig.StreamableHttp(url, Map.of(), INIT, EXEC), null, 100);
    }

    private static void assertInvalid(McpConfig.MCP server, String expectedFragment) {
        IllegalArgumentException error = assertThrows(IllegalArgumentException.class,
                () -> McpValidator.validate(server));
        assertTrue(error.getMessage().contains(expectedFragment),
                "expected '" + expectedFragment + "' in: " + error.getMessage());
    }
}
