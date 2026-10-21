package com.summit.adapter.langchain4j.model.provider;

import com.summit.core.conf.ModelConfig;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class AnthropicModelSettingsTest {
    @Test
    void mapsEffortAliasesAndDisablesThinkingForNone() {
        assertEquals(java.util.Map.of("output_config", java.util.Map.of("effort", "low")),
                resolve("claude-sonnet-4-6", "minimal").customParameters());
        assertEquals(java.util.Map.of("output_config", java.util.Map.of("effort", "high")),
                resolve("claude-opus-4-6", "xhigh").customParameters());
        AnthropicModelSettings none = resolve("claude-opus-4-6", "none");
        assertEquals("disabled", none.thinkingType());
        assertTrue(none.customParameters().isEmpty());
        assertNull(none.budget());
    }

    @Test
    void manualModelsUseBudgetsWithoutUnsupportedEffort() {
        for (String model : java.util.List.of("claude-3-7-sonnet-latest", "claude-sonnet-4-5",
                "claude-sonnet-4-20250514", "claude-opus-4-1")) {
            AnthropicModelSettings settings = resolve(model, "medium");
            assertEquals("enabled", settings.thinkingType());
            assertEquals(4096, settings.budget());
            assertTrue(settings.customParameters().isEmpty());
        }
        assertFalse(resolve("claude-opus-4-5", "high").customParameters().isEmpty());
    }

    @Test
    void explicitThinkingAndBudgetOverrideInferenceAndRejectInvalidCombinations() {
        ModelConfig config = config("claude-opus-4-6", "high").anthropicOptions(ModelConfig.AnthropicOptions.builder()
                        .thinkingType("enabled").thinkingBudgetTokens(2048).build()).build();
        assertEquals(2048, AnthropicModelSettings.resolve(config).budget());
        assertThrows(IllegalArgumentException.class, () -> AnthropicModelSettings.resolve(
                config.toBuilder().anthropicOptions(config.getAnthropicOptions().toBuilder().thinkingType("adaptive").build()).build()));
        assertThrows(IllegalArgumentException.class, () -> AnthropicModelSettings.resolve(
                config.toBuilder().anthropicOptions(config.getAnthropicOptions().toBuilder().thinkingBudgetTokens(500).build()).build()));
        assertThrows(IllegalArgumentException.class, () -> AnthropicModelSettings.resolve(
                config.toBuilder().anthropicOptions(config.getAnthropicOptions().toBuilder().thinkingBudgetTokens(32768).build()).build()));
        assertThrows(IllegalArgumentException.class, () -> resolve("claude-opus-4-6", "unknown"));
    }

    @Test
    void promptCacheSettingsNormalizeTheTtlAndRejectUnknownValues() {
        AnthropicModelSettings cached = AnthropicModelSettings.resolve(config("claude-sonnet-4-6", "low")
                .anthropicOptions(ModelConfig.AnthropicOptions.builder()
                        .promptCacheTtl(" 1H ").promptCacheConversation(true).build()).build());
        assertEquals("1h", cached.cacheTtl());
        assertTrue(cached.cacheAutomatically());
        AnthropicModelSettings plain = resolve("claude-sonnet-4-6", "low");
        assertNull(plain.cacheTtl());
        assertFalse(plain.cacheAutomatically());
        assertThrows(IllegalArgumentException.class, () -> AnthropicModelSettings.resolve(
                config("claude-sonnet-4-6", "low").anthropicOptions(ModelConfig.AnthropicOptions.builder().promptCacheTtl("2h").build()).build()));
    }

    private AnthropicModelSettings resolve(String model, String effort) {
        return AnthropicModelSettings.resolve(config(model, effort).build());
    }

    private ModelConfig.ModelConfigBuilder config(String model, String effort) {
        return ModelConfig.builder().baseUrl("http://localhost/v1/").apiKey("test-key")
                .modelName(model).reasoningEffort(effort).maxTokens(32768);
    }
}
