package anthropic;

import com.summit.core.conf.ModelConfig;
import com.summit.core.conf.ReasoningEffortPlan;
import java.util.Locale;
import java.util.Set;

/** Maps harness effort to Anthropic adaptive or manual thinking without changing OpenAI settings. */
record AnthropicModelSettings(String thinkingType, Integer budget, int maxTokens, boolean cachePrefix, String cacheTtl,
                              boolean cacheAutomatically, String outputEffort) {
    static AnthropicModelSettings resolve(ModelConfig config) {
        ModelConfig.AnthropicOptions options = config.getAnthropicOptions();
        int maxTokens = config.getMaxTokens() == null ? 32768 : config.getMaxTokens();
        if (maxTokens <= 0) throw new IllegalArgumentException("Anthropic maxTokens must be positive");
        String effort = normalize(config.getReasoningEffort());
        if (effort != null && !Set.of("none", "minimal", "low", "medium", "high", "xhigh", "max").contains(effort)) {
            throw new IllegalArgumentException("Unsupported Anthropic reasoning effort: " + effort);
        }
        // 档位语义（xhigh 折 high、manual 判定、预算表）只在 ReasoningEffortPlan 里有一份：
        // 业务侧要按它做能力声明与配置校验，这里再抄一遍必然漂移。
        String model = normalize(config.getModelName());
        boolean manualModel = ReasoningEffortPlan.manualThinking(model);
        String type = normalize(options == null ? null : options.thinkingType());
        Integer budget = options == null ? null : options.thinkingBudgetTokens();
        if (type == null) {
            type = inferredThinkingType(effort, budget, model, manualModel);
        }
        if (type != null && !Set.of("disabled", "enabled", "adaptive").contains(type)) {
            throw new IllegalArgumentException("Unsupported Anthropic thinking type: " + type);
        }
        if ("enabled".equals(type)) {
            if (budget == null) {
                budget = ReasoningEffortPlan.thinkingBudget(effort);
                budget = Math.min(budget, maxTokens - 1);
            }
            if (budget < 1024 || budget >= maxTokens) {
                throw new IllegalArgumentException("Anthropic thinking budget must be >= 1024 and below maxTokens");
            }
        } else if (budget != null) {
            throw new IllegalArgumentException("Anthropic thinkingBudgetTokens requires enabled thinking");
        }
        String outputEffort = ReasoningEffortPlan.supportsNativeEffort(model) && effort != null && !"none".equals(effort)
                ? ReasoningEffortPlan.canonical("anthropic", model, effort) : null;
        return new AnthropicModelSettings(type, budget, maxTokens, options != null && options.promptCache(),
                cacheTtl(options), options != null && options.promptCacheConversation(), outputEffort);
    }

    /**
     * What to send when the caller named no thinking mode.
     *
     * <p>A model name this framework cannot classify gets no thinking block at all. An endpoint may
     * serve anything under any name, and a parameter the provider does not know is a 400 that is
     * not retried: guessing {@code adaptive} for an unreadable name is how a working chat turns into
     * a terminal failure after a model is renamed or reached through a relay alias.</p>
     */
    private static String inferredThinkingType(String effort, Integer budget, String model, boolean manualModel) {
        if ("none".equals(effort)) return "disabled";
        if (budget != null) return "enabled";
        if (effort == null) return null;
        if (manualModel) return "enabled";
        return ReasoningEffortPlan.adaptiveThinking(model) ? "adaptive" : null;
    }

    /** Anthropic sends the TTL verbatim inside every cache breakpoint, so only its two values pass. */
    private static String cacheTtl(ModelConfig.AnthropicOptions options) {
        String ttl = normalize(options == null ? null : options.promptCacheTtl());
        if (ttl == null) return null;
        if (!Set.of("5m", "1h").contains(ttl)) {
            throw new IllegalArgumentException("Unsupported Anthropic prompt cache TTL: " + ttl);
        }
        return ttl;
    }

    private static String normalize(String value) {
        return value == null || value.isBlank() ? null : value.strip().toLowerCase(Locale.ROOT);
    }
}
