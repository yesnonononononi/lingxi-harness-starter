package com.summit.core.conf;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.summit.core.json.ExecutionJson;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Stored stdio configuration predates the working directory, so it has to stay readable without one. */
class McpConfigJsonTest {

    private final ObjectMapper mapper = ExecutionJson.newObjectMapper();

    @Test
    void stdioStoredWithoutWorkDirStillDeserializes() throws Exception {
        McpConfig config = mapper.readValue("""
                {"mcp":[{"name":"playwright","description":null,"transport":"STDIO","maxOutput":1000,
                "conf":{"type":"stdio","command":["npx.cmd","--yes","@playwright/mcp@latest"],"env":{},
                "initializationTimeout":"PT3S","executionTimeout":"PT4S"}}]}
                """, McpConfig.class);

        McpConfig.Stdio stdio = (McpConfig.Stdio) config.getMcp().getFirst().conf();
        assertNull(stdio.workDir());
        assertEquals(List.of("npx.cmd", "--yes", "@playwright/mcp@latest"), stdio.command());
        assertEquals(Duration.ofSeconds(3), stdio.initializationTimeout());
    }

    @Test
    void workDirSurvivesARoundTrip() throws Exception {
        McpConfig.Stdio original = new McpConfig.Stdio(List.of("npx.cmd"), Map.of("VISIBLE", "yes"),
                "D:/work", Duration.ofSeconds(3), Duration.ofSeconds(4));

        String json = mapper.writeValueAsString(original);

        assertTrue(json.contains("\"workDir\":\"D:/work\""), json);
        assertEquals(original, mapper.readValue(json, McpConfig.Stdio.class));
    }

    /**
     * The four client-tuning fields are optional. A configuration stored before they existed must
     * still load, and every one of them must come back as {@code null} — a substituted default
     * would silently change how the old entry connects.
     */
    @Test
    void storedConfigWithoutClientTuningLoadsWithNulls() throws Exception {
        McpConfig config = mapper.readValue("""
                {"mcp":[
                  {"name":"github","description":null,"transport":"STREAMABLE_HTTP","maxOutput":1000,
                   "conf":{"type":"streamable-http","url":"https://api.githubcopilot.com/mcp/","headers":{},
                   "initializationTimeout":"PT3S","executionTimeout":"PT4S"}},
                  {"name":"playwright","description":null,"transport":"STDIO","maxOutput":1000,
                   "conf":{"type":"stdio","command":["npx.cmd","--yes","@playwright/mcp@latest"],"env":{},
                   "initializationTimeout":"PT3S","executionTimeout":"PT4S"}}]}
                """, McpConfig.class);

        McpConfig.StreamableHttp http = (McpConfig.StreamableHttp) config.getMcp().get(0).conf();
        assertNull(http.protocolVersion());
        assertNull(http.autoHealthCheck());
        assertNull(http.healthCheckInterval());
        assertNull(http.reconnectInterval());

        McpConfig.Stdio stdio = (McpConfig.Stdio) config.getMcp().get(1).conf();
        assertNull(stdio.protocolVersion());
        assertNull(stdio.autoHealthCheck());
        assertNull(stdio.healthCheckInterval());
        assertNull(stdio.reconnectInterval());
    }

    /** A fully configured entry must survive serialization unchanged, tuning fields included. */
    @Test
    void clientTuningSurvivesARoundTrip() throws Exception {
        McpConfig.StreamableHttp http = new McpConfig.StreamableHttp("https://mcp.context7.com/mcp",
                Map.of("Accept", "application/json, text/event-stream"),
                Duration.ofSeconds(3), Duration.ofSeconds(4),
                "2025-11-25", Boolean.FALSE, Duration.ofSeconds(30), Duration.ofSeconds(5));
        McpConfig original = new McpConfig();
        original.setMcp(List.of(new McpConfig.MCP("context7", null, McpTransport.STREAMABLE_HTTP, http, 1000)));

        String json = mapper.writeValueAsString(original);

        McpConfig roundTripped = mapper.readValue(json, McpConfig.class);
        assertEquals(http, roundTripped.getMcp().getFirst().conf());
        assertEquals("2025-11-25", mapper.readTree(json).path("mcp").get(0).path("conf")
                .path("protocolVersion").asText());
        assertFalse(mapper.readTree(json).path("mcp").get(0).path("conf")
                .path("autoHealthCheck").asBoolean());
    }

    /** The protocol version is a plain JSON string and must land in the matching component. */
    @Test
    void protocolVersionIsReadFromAPlainString() throws Exception {
        McpConfig config = mapper.readValue("""
                {"mcp":[{"name":"draw.io","description":null,"transport":"STREAMABLE_HTTP","maxOutput":1000,
                "conf":{"type":"streamable-http","url":"https://mcp.draw.io/mcp","headers":{},
                 "initializationTimeout":"PT3S","executionTimeout":"PT4S",
                 "protocolVersion":"2025-11-25","autoHealthCheck":true,
                 "healthCheckInterval":"PT30S","reconnectInterval":"PT5S"}}]}
                """, McpConfig.class);

        McpConfig.StreamableHttp http = (McpConfig.StreamableHttp) config.getMcp().getFirst().conf();
        assertEquals("2025-11-25", http.protocolVersion());
        assertEquals(Boolean.TRUE, http.autoHealthCheck());
        assertEquals(Duration.ofSeconds(30), http.healthCheckInterval());
        assertEquals(Duration.ofSeconds(5), http.reconnectInterval());
    }
}
