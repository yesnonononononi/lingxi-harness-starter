package com.summit.adapter.langchain4j.model.provider;

import com.summit.core.conf.ModelConfig;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/** Maps harness effort to Anthropic adaptive or manual thinking without changing OpenAI settings. */
record AnthropicModelSettings(String thinkingType, Integer budget, int maxTokens, Map<String, Object> customParameters) {
    static AnthropicModelSettings resolve(ModelConfig config) {
        int maxTokens = config.getMaxTokens() == null ? 32768 : config.getMaxTokens();
        if (maxTokens <= 0) throw new IllegalArgumentException("Anthropic maxTokens must be positive");
        String effort = normalize(config.getReasoningEffort());
        if (effort != null && !Set.of("none", "minimal", "low", "medium", "high", "xhigh", "max").contains(effort)) {
            throw new IllegalArgumentException("Unsupported Anthropic reasoning effort: " + effort);
        }
        String model = config.getModelName().strip().toLowerCase(Locale.ROOT);
        boolean manualModel = model.startsWith("claude-3")
                || model.matches("claude-(opus|sonnet|haiku)-4(?:-(?:0|1|5)(?:-.*)?|-\\d{8})?");
        String type = normalize(config.getThinkingType());
        Integer budget = config.getThinkingBudgetTokens();
        if (type == null) {
            type = "none".equals(effort) ? "disabled"
                    : budget != null ? "enabled"
                    : effort == null ? null : manualModel ? "enabled" : "adaptive";
        }
        if (type != null && !Set.of("disabled", "enabled", "adaptive").contains(type)) {
            throw new IllegalArgumentException("Unsupported Anthropic thinking type: " + type);
        }
        if ("enabled".equals(type)) {
            if (budget == null) {
                budget = switch (effort == null ? "low" : effort) {
                    case "medium" -> 4096;
                    case "high", "xhigh" -> 8192;
                    case "max" -> 16384;
                    default -> 1024;
                };
                budget = Math.min(budget, maxTokens - 1);
            }
            if (budget < 1024 || budget >= maxTokens) {
                throw new IllegalArgumentException("Anthropic thinking budget must be >= 1024 and below maxTokens");
            }
        } else if (budget != null) {
            throw new IllegalArgumentException("Anthropic thinkingBudgetTokens requires enabled thinking");
        }
        // The pinned SDK has no typed effort field; customParameters emits the native output_config.
        // Older manual-thinking models accept a budget, but only Opus 4.5 also supports effort.
        boolean supportsEffort = !manualModel || model.startsWith("claude-opus-4-5");
        Map<String, Object> parameters = Map.of();
        if (supportsEffort && effort != null && !"none".equals(effort)) {
            String mapped = switch (effort) {
                case "minimal" -> "low";
                case "xhigh" -> "high";
                default -> effort;
            };
            parameters = Map.of("output_config", Map.of("effort", mapped));
        }
        return new AnthropicModelSettings(type, budget, maxTokens, parameters);
    }

    private static String normalize(String value) {
        return value == null || value.isBlank() ? null : value.strip().toLowerCase(Locale.ROOT);
    }
}
