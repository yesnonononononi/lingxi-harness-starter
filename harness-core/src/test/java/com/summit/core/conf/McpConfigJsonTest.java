package com.summit.core.conf;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.summit.core.json.ExecutionJson;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
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
}
