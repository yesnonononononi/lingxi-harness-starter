package com.summit.core.conf;

import com.summit.core.json.ExecutionJson;
import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class ModelConfigJsonTest {
    @Test
    void nestedOptionsAndRetryCountSurviveRoundTrip() throws Exception {
        var mapper = ExecutionJson.newObjectMapper();
        ModelConfig config = ModelConfig.builder().provider("default-image-model").maxRetries(0)
                .imageOptions(new ModelConfig.ImageOptions("high", "1024x1024", Map.of("X-Route", "images")))
                .anthropicOptions(new ModelConfig.AnthropicOptions("enabled", 2048, true, "1h", true)).build();
        String json = mapper.writeValueAsString(config);
        assertEquals(config, mapper.readValue(json, ModelConfig.class));
        var tree = mapper.readTree(json);
        assertEquals("enabled", tree.path("anthropicOptions").path("thinkingType").asText());
        assertEquals("images", tree.path("imageOptions").path("customHeaders").path("X-Route").asText());
        assertFalse(tree.has("thinkingType"));
        assertFalse(tree.has("promptCache"));
    }

    @Test
    void absentOptionsAndHeadersKeepUsableDefaults() throws Exception {
        var mapper = ExecutionJson.newObjectMapper();
        for (ModelConfig config : new ModelConfig[]{ModelConfig.builder().build(), mapper.readValue("{}", ModelConfig.class)}) {
            assertEquals(3, config.getMaxRetries());
            assertNull(config.getImageOptions());
            assertNull(config.getAnthropicOptions());
        }
        assertTrue(new ModelConfig.ImageOptions(null, null, null).customHeaders().isEmpty());
        assertTrue(ModelConfig.ImageOptions.builder().build().customHeaders().isEmpty());
        assertTrue(mapper.readValue("{\"imageOptions\":{}}", ModelConfig.class).getImageOptions().customHeaders().isEmpty());
    }

    @Test
    void headersAreAnImmutableSnapshot() {
        Map<String, String> headers = new HashMap<>(Map.of("X-Route", "images"));
        var options = new ModelConfig.ImageOptions(null, null, headers);
        headers.put("X-Route", "changed");
        assertEquals("images", options.customHeaders().get("X-Route"));
        assertThrows(UnsupportedOperationException.class, () -> options.customHeaders().put("X-Route", "changed"));
    }
}
