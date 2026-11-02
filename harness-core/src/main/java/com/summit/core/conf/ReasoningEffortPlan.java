package com.summit.core.conf;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * What a reasoning-effort level actually means on one concrete model.
 *
 * <p>The level vocabulary is shared, but the wire semantics are not: Anthropic folds {@code xhigh}
 * into {@code high} (and {@code minimal} into {@code low}) and expresses effort as a thinking
 * budget or an {@code output_config.effort}, while OpenAI-compatible endpoints take the value
 * verbatim. Keeping that knowledge in one place — here — lets the adapter, the business layer and
 * the UI agree instead of each carrying a copy that drifts. See {@code AnthropicModelSettings},
 * which no longer owns any mapping of its own.</p>
 */
public final class ReasoningEffortPlan {

    /** Every level the request layer accepts, weakest first. */
    public static final List<String> LEVELS = List.of("none", "minimal", "low", "medium", "high", "xhigh", "max");

    private static final Set<String> KNOWN = Set.of("none", "minimal", "low", "medium", "high", "xhigh", "max");

    private ReasoningEffortPlan() {
    }

    /** Whether effort has to be expressed as an Anthropic thinking budget / native effort. */
    public static boolean anthropicFamily(String provider, String modelName) {
        String normalizedProvider = normalize(provider);
        if (normalizedProvider != null && normalizedProvider.contains("anthropic")) return true;
        String normalizedModel = normalize(modelName);
        return normalizedModel != null && normalizedModel.startsWith("claude");
    }

    /**
     * Models whose thinking is expressed as a manual token budget (claude-3*, claude-4.0/4.1/4.5 and
     * dated snapshots) instead of the adaptive effort parameter newer models accept.
     */
    public static boolean manualThinking(String modelName) {
        String model = normalize(modelName);
        if (model == null) return false;
        return model.startsWith("claude-3")
                || model.matches("claude-(opus|sonnet|haiku)-4(?:-(?:0|1|5)(?:-.*)?|-\\d{8})?");
    }

    /** Whether the model accepts the native effort parameter on top of a manual thinking budget. */
    public static boolean supportsNativeEffort(String modelName) {
        String model = normalize(modelName);
        return model == null || !manualThinking(model) || model.startsWith("claude-opus-4-5");
    }

    /** The value that actually reaches the provider; {@code xhigh}/{@code minimal} collapse on Anthropic. */
    public static String canonical(String provider, String modelName, String effort) {
        String level = normalize(effort);
        if (level == null) return null;
        if (!anthropicFamily(provider, modelName)) return level;
        return switch (level) {
            case "xhigh" -> "high";
            case "minimal" -> "low";
            default -> level;
        };
    }

    /**
     * Levels that are genuinely distinct on this model, weakest first.
     *
     * <p>Unknown providers get the full list: we cannot know which levels a third-party endpoint
     * accepts, so the UI keeps offering them rather than pretending to know.</p>
     */
    public static List<String> distinct(String provider, String modelName) {
        if (!anthropicFamily(provider, modelName)) return LEVELS;
        List<String> levels = new ArrayList<>(LEVELS.size());
        for (String level : LEVELS) {
            // xhigh 与 high、minimal 与 low 在 Anthropic 上生成完全相同的请求：
            // 列出来就是承诺一个不存在的区别。
            if (!"xhigh".equals(level) && !"minimal".equals(level)) levels.add(level);
        }
        return List.copyOf(levels);
    }

    /** Thinking budget a manual Anthropic model needs for one level. */
    public static int thinkingBudget(String effort) {
        return switch (normalize(effort) == null ? "low" : normalize(effort)) {
            case "medium" -> 4096;
            case "high", "xhigh" -> 8192;
            case "max" -> 16384;
            default -> 1024;
        };
    }

    /**
     * Why a level cannot be used with this model and output budget, or {@code null} when it can.
     *
     * <p>The rules mirror what the adapter enforces when it builds the client, so a bad pair is
     * reported as a business message at configuration time instead of an English exception thrown
     * in the middle of a conversation.</p>
     */
    public static String invalidReason(String provider, String modelName, String effort, Integer maxTokens) {
        String level = normalize(effort);
        if (level == null) return null;
        if (!KNOWN.contains(level)) return "不支持的思考强度: " + effort;
        if (!anthropicFamily(provider, modelName)) return null;
        // none 关闭思考，不占预算
        if ("none".equals(level)) return null;
        if (!manualThinking(modelName) || maxTokens == null) return null;
        int budget = thinkingBudget(level);
        return budget >= maxTokens
                ? "思考强度 " + level + " 的思考预算 " + budget + " 必须小于输出上限 " + maxTokens
                : null;
    }

    /** The subset of {@link #LEVELS} that is legal for this model and output budget. */
    public static List<String> usable(String provider, String modelName, Integer maxTokens) {
        List<String> usable = new ArrayList<>();
        for (String level : distinct(provider, modelName)) {
            if (invalidReason(provider, modelName, level, maxTokens) == null) usable.add(level);
        }
        return List.copyOf(usable);
    }

    private static String normalize(String value) {
        return value == null || value.isBlank() ? null : value.strip().toLowerCase(Locale.ROOT);
    }
}
