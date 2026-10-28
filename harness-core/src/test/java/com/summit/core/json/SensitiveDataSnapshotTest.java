package com.summit.core.json;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.summit.core.agent.AgentRequest;
import com.summit.core.agent.Execution;
import com.summit.core.conf.McpConfig;
import com.summit.core.conf.McpTransport;
import com.summit.core.conf.SensitiveDataSpec;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class SensitiveDataSnapshotTest {
    private final ObjectMapper mapper = ExecutionJson.newObjectMapper();

    @Test
    void legacyMcpCredentialsAreAcceptedButNeverWrittenBack() throws Exception {
        McpConfig config = new McpConfig();
        config.setMcp(List.of(
                new McpConfig.MCP("http", null, McpTransport.STREAMABLE_HTTP,
                        new McpConfig.StreamableHttp("https://example.invalid", Map.of("Authorization", "header-secret-canary"), null, null), 100),
                new McpConfig.MCP("stdio", null, McpTransport.STDIO,
                        new McpConfig.Stdio(List.of("example"), Map.of("TOKEN", "env-secret-canary"), null, null), 100)));
        String rawConfig = mapper.writeValueAsString(config);
        assertTrue(rawConfig.contains("header-secret-canary"));
        assertTrue(rawConfig.contains("env-secret-canary"));

        Execution restored = mapper.readValue("{\"id\":\"legacy\",\"agentRequest\":{},\"mcpConfig\":" + rawConfig + "}", Execution.class);
        assertEquals(config, restored.getMcpConfig());
        String snapshot = mapper.writeValueAsString(restored);
        assertFalse(mapper.readTree(snapshot).has("mcpConfig"));
        assertFalse(snapshot.contains("header-secret-canary"));
        assertFalse(snapshot.contains("env-secret-canary"));
    }

    @Test
    void omittedVersionRestoresTheDefaultRevision() throws Exception {
        SensitiveDataSpec restored = mapper.readValue("{\"id\":31}", SensitiveDataSpec.class);
        assertEquals(31, restored.getId().intValue());
        assertEquals(1, restored.getVersion());
        AgentRequest request = AgentRequest.builder().modelConfigSpec(restored).build();
        assertEquals(1, mapper.readValue(mapper.writeValueAsString(request), AgentRequest.class)
                .getModelConfigSpec().getVersion());
    }
}
