package com.summit.core.conf;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The single source of tier semantics: on Anthropic xhigh/minimal mean high/low, while the OpenAI
 * protocol passes the level through untouched.
 *
 * <p>The same rules are consumed by the adapter (which decides what to send) and by the business
 * layer (which decides how many levels the UI offers and whether a configuration is legal), so they
 * are pinned here in the framework rather than re-tested by each caller from its own copy.</p>
 */
class ReasoningEffortPlanTest {

    @Test
    @DisplayName("The OpenAI protocol keeps every level verbatim")
    void openAiKeepsEveryLevel() {
        assertEquals(ReasoningEffortPlan.LEVELS, ReasoningEffortPlan.distinct("openai", "gpt-5"));
        assertEquals("xhigh", ReasoningEffortPlan.canonical("openai", "gpt-5", "xhigh"));
        assertEquals("minimal", ReasoningEffortPlan.canonical("openai", "gpt-5", "minimal"));
        assertNull(ReasoningEffortPlan.invalidReason("openai", "gpt-5", "max", 1024),
                "the server owns the budget on the OpenAI protocol; the framework does not cross-check it");
    }

    @Test
    @DisplayName("Anthropic folds xhigh into high and minimal into low, and stops listing them")
    void anthropicCollapsesIndistinguishableLevels() {
        for (String model : List.of("claude-3-5-sonnet-20241022", "claude-sonnet-4-5", "claude-sonnet-4-6")) {
            assertEquals("high", ReasoningEffortPlan.canonical("anthropic", model, "xhigh"), model);
            assertEquals("low", ReasoningEffortPlan.canonical("anthropic", model, "minimal"), model);
            List<String> levels = ReasoningEffortPlan.distinct("anthropic", model);
            assertFalse(levels.contains("xhigh"), "xhigh makes no separate request on " + model);
            assertFalse(levels.contains("minimal"), "minimal makes no separate request on " + model);
            assertTrue(levels.contains("max"), "max is a genuinely distinct level: " + model);
        }
        assertEquals(List.of("none", "low", "medium", "high", "max"),
                ReasoningEffortPlan.distinct("anthropic", "claude-sonnet-4-6"));
    }

    @Test
    @DisplayName("Manual thinking detection and the Opus 4.5 exception")
    void manualThinkingDetection() {
        assertTrue(ReasoningEffortPlan.manualThinking("claude-3-5-haiku-latest"));
        assertTrue(ReasoningEffortPlan.manualThinking("claude-sonnet-4-5"));
        assertTrue(ReasoningEffortPlan.manualThinking("claude-opus-4-1-20250805"));
        assertFalse(ReasoningEffortPlan.manualThinking("claude-sonnet-4-6"), "4.6 uses adaptive thinking");
        assertFalse(ReasoningEffortPlan.manualThinking("gpt-5"));
        assertTrue(ReasoningEffortPlan.supportsNativeEffort("claude-opus-4-5"));
        assertFalse(ReasoningEffortPlan.supportsNativeEffort("claude-sonnet-4-5"));
    }

    @Test
    @DisplayName("adaptive thinking needs a readable Claude version, otherwise nothing is sent")
    void adaptiveThinkingRequiresARecognisableModelName() {
        assertTrue(ReasoningEffortPlan.adaptiveThinking("claude-sonnet-4-6"));
        assertTrue(ReasoningEffortPlan.adaptiveThinking("claude-opus-4-7"), "a newer minor is still adaptive");
        assertTrue(ReasoningEffortPlan.adaptiveThinking("claude-5-opus-20260101"), "the older naming reads too");
        assertFalse(ReasoningEffortPlan.adaptiveThinking("claude-sonnet-4-5"), "4.5 takes a manual budget");
        assertFalse(ReasoningEffortPlan.adaptiveThinking("claude-3-5-sonnet-20241022"), "3.x takes a manual budget");
        assertFalse(ReasoningEffortPlan.adaptiveThinking("claude-3-opus-20240229"),
                "a name without a minor is not the adaptive generation");
        // Nothing at all for a name we cannot read: a relay alias, a typo, an OpenAI model.
        assertFalse(ReasoningEffortPlan.adaptiveThinking(null));
        assertFalse(ReasoningEffortPlan.adaptiveThinking("claude"));
        assertFalse(ReasoningEffortPlan.adaptiveThinking("deepseek/deepseek-v4.1-flash"));
        assertFalse(ReasoningEffortPlan.adaptiveThinking("gpt-5"));
        // Claiming native effort would send output_config along with it.
        assertFalse(ReasoningEffortPlan.supportsNativeEffort(null));
        assertFalse(ReasoningEffortPlan.supportsNativeEffort("gpt-5"));
        assertTrue(ReasoningEffortPlan.supportsNativeEffort("claude-sonnet-4-6"));
    }

    @Test
    @DisplayName("Level and output budget are cross-checked: the thinking budget must stay below the cap")
    void effortAndOutputBudgetAreCrossChecked() {
        // Only manual-thinking models are subject to this rule.
        assertNull(ReasoningEffortPlan.invalidReason("anthropic", "claude-sonnet-4-5", "max", 32768));
        assertTrue(ReasoningEffortPlan.invalidReason("anthropic", "claude-sonnet-4-5", "max", 4096).contains("16384"),
                "the message must state both the budget and the cap");
        assertTrue(ReasoningEffortPlan.invalidReason("anthropic", "claude-sonnet-4-5", "low", 1024) != null,
                "a budget of 1024 is not below a cap of 1024 and must be rejected, "
                        + "instead of surfacing as an English exception when the model is built");
        // "none" switches thinking off, so it spends no budget.
        assertNull(ReasoningEffortPlan.invalidReason("anthropic", "claude-sonnet-4-5", "none", 512));
        // An adaptive model carries no budget, so the cap cannot bind.
        assertNull(ReasoningEffortPlan.invalidReason("anthropic", "claude-sonnet-4-6", "max", 4096));
        // An unknown level names the offending value.
        assertTrue(ReasoningEffortPlan.invalidReason("openai", "gpt-5", "ultra", 32768).contains("ultra"));
        assertEquals(List.of("none"), ReasoningEffortPlan.usable("anthropic", "claude-sonnet-4-5", 1024));
    }
}
