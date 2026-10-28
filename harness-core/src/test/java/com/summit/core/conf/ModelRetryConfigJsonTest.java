package com.summit.core.conf;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.summit.core.agent.AgentRequest;
import com.summit.core.json.ExecutionJson;
import org.junit.jupiter.api.Test;

import java.time.Duration;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ModelRetryConfigJsonTest {
    @Test
    void requestCarriesNestedSettingsWithoutExecutableCallbacks() throws Exception {
        ObjectMapper mapper = ExecutionJson.newObjectMapper();
        AgentRequest request = AgentRequest.builder().retryConfig(ModelRetryConfig.builder()
                .enabled(true).maxRetries(2).initialDelay(Duration.ofMillis(37)).multiplier(3)
                .maxDelay(Duration.ofMillis(140)).build()).build();
        String json = mapper.writeValueAsString(request);
        JsonNode root = mapper.readTree(json);
        JsonNode settings = root.path("retryConfig");
        assertTrue(settings.path("enabled").asBoolean());
        assertEquals(2, settings.path("maxRetries").asInt());
        assertEquals(5, settings.size());
        assertFalse(root.has("maxRetries"));
        assertFalse(settings.has("task"));
        assertFalse(settings.has("signal"));
        assertFalse(settings.has("execRequireRetry"));

        ModelRetryConfig restored = mapper.readValue(json, AgentRequest.class).getRetryConfig();
        assertTrue(restored.isEnabled());
        assertEquals(2, restored.getMaxRetries());
        assertEquals(Duration.ofMillis(37), restored.getInitialDelay());
        assertEquals(3, restored.getMultiplier());
        assertEquals(Duration.ofMillis(140), restored.getMaxDelay());
    }

    @Test
    void absentAndDefaultSettingsKeepRetriesDisabled() throws Exception {
        ObjectMapper mapper = ExecutionJson.newObjectMapper();
        assertNull(AgentRequest.builder().build().getRetryConfig());
        assertNull(mapper.readValue("{}", AgentRequest.class).getRetryConfig());
        for (ModelRetryConfig settings : new ModelRetryConfig[] {
                ModelRetryConfig.builder().build(), mapper.readValue("{}", ModelRetryConfig.class)}) {
            assertFalse(settings.isEnabled());
            assertEquals(3, settings.getMaxRetries());
            assertEquals(Duration.ofSeconds(10), settings.getInitialDelay());
            assertEquals(2, settings.getMultiplier());
            assertEquals(Duration.ofSeconds(10), settings.getMaxDelay());
        }
    }
}
