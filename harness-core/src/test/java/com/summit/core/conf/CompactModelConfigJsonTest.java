package com.summit.core.conf;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.summit.core.json.ExecutionJson;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class CompactModelConfigJsonTest {
    private final ObjectMapper mapper = ExecutionJson.newObjectMapper();

    @Test
    void compactionSelectionSurvivesJsonAndBuilderCopies() throws Exception {
        ModelConfig config = ModelConfig.builder().reuseModelForCompaction(true)
                .compactModel(new ModelConfig.CompactModelConfig("https://compact.example/v1", "compact-key", "summary"))
                .build();
        ModelConfig restored = mapper.readValue(mapper.writeValueAsString(config), ModelConfig.class);
        assertEquals(config, restored);
        assertTrue(restored.isReuseModelForCompaction());
        assertEquals(config.getCompactModel(), restored.toBuilder().provider("default").build().getCompactModel());
        assertTrue(restored.toBuilder().provider("default").build().isReuseModelForCompaction());
        assertEquals(config.getCompactModel(), ModelConfig.CompactModelConfig.builder()
                .baseUrl("https://compact.example/v1").apiKey("compact-key").modelName("summary").build());
    }

    @Test
    void legacyConfigurationsRetainApplicationCompactionSelection() throws Exception {
        ModelConfig legacy = mapper.readValue("{}", ModelConfig.class);
        assertFalse(legacy.isReuseModelForCompaction());
        assertNull(legacy.getCompactModel());
    }
}
