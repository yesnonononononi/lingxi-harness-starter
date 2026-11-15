package com.summit.core.conf;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

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

    /**
     * The version every Claude name carries, in either naming shape: {@code claude-3-5-sonnet-20241022}
     * and {@code claude-sonnet-4-6} read as 3.5 and 4.6.
     *
     * <p>The optional word group is why the tier never needs naming here: a new tier is just another
     * word in the model name, not another branch in this class.</p>
     */
    private static final Pattern CLAUDE_VERSION = Pattern.compile("claude(?:-[a-z]+)*-(\\d+)(?:-(\\d+))?.*");
    /** First generation that accepts {@code thinking.type=adaptive}; everything below is a manual budget. */
    private static final int ADAPTIVE_MAJOR = 4;
    private static final int ADAPTIVE_MINOR = 6;
    /** The one model that takes a native effort parameter on top of a manual budget. */
    private static final String NATIVE_EFFORT_WITH_BUDGET = "claude-opus-4-5";

    /** Whether effort has to be expressed as an Anthropic thinking budget / native effort. */
    public static boolean anthropicFamily(String provider, String modelName) {
        String normalizedProvider = normalize(provider);
        if (normalizedProvider != null && normalizedProvider.contains("anthropic")) return true;
        String normalizedModel = normalize(modelName);
        return normalizedModel != null && normalizedModel.startsWith("claude");
    }

    /**
     * Models whose thinking is expressed as a manual token budget — everything older than the
     * adaptive generation, dated snapshots included.
     *
     * <p>Decided by the version in the name alone; a name with no readable Claude version is not a
     * manual-thinking model, which keeps an unclassifiable name from matching by accident.</p>
     */
    public static boolean manualThinking(String modelName) {
        int[] version = claudeVersion(modelName);
        return version != null && compare(version, ADAPTIVE_MAJOR, ADAPTIVE_MINOR) < 0;
    }

    /**
     * Whether the model belongs to the generation that expresses thinking as adaptive effort instead
     * of an explicit token budget.
     *
     * <p>Only names whose version can actually be read count. An endpoint may serve anything under
     * any name — a relay's own alias, a renamed model, a typo — and sending a parameter the provider
     * does not know is a 400 that is never retried. A name this class cannot read therefore gets no
     * thinking block and no {@code output_config} at all, rather than the newest protocol as a
     * guess.</p>
     */
    public static boolean adaptiveThinking(String modelName) {
        int[] version = claudeVersion(modelName);
        return version != null && compare(version, ADAPTIVE_MAJOR, ADAPTIVE_MINOR) >= 0;
    }

    /** Whether the model accepts the native effort parameter on top of a manual thinking budget. */
    public static boolean supportsNativeEffort(String modelName) {
        String model = normalize(modelName);
        return model != null && (adaptiveThinking(model) || model.startsWith(NATIVE_EFFORT_WITH_BUDGET));
    }

    private static int compare(int[] version, int major, int minor) {
        return version[0] == major ? Integer.compare(version[1], minor) : Integer.compare(version[0], major);
    }

    /**
     * Reads the model version out of a Claude name.
     *
     * @return {@code {major, minor}}, minor 0 when the name carries none, or {@code null} when the
     *         name is not a Claude model version at all
     */
    private static int[] claudeVersion(String modelName) {
        String model = normalize(modelName);
        if (model == null) return null;
        Matcher matched = CLAUDE_VERSION.matcher(model);
        if (!matched.matches()) return null;
        String minor = matched.group(2);
        return new int[]{Integer.parseInt(matched.group(1)), minor == null ? 0 : Integer.parseInt(minor)};
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
            // xhigh and minimal produce exactly the same request as high and low on Anthropic:
            // listing them would promise a difference that does not exist.
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
        if (!KNOWN.contains(level)) return "Unsupported reasoning effort: " + effort;
        if (!anthropicFamily(provider, modelName)) return null;
        // "none" switches thinking off, so it spends no budget.
        if ("none".equals(level)) return null;
        if (!manualThinking(modelName) || maxTokens == null) return null;
        int budget = thinkingBudget(level);
        return budget >= maxTokens
                ? "Thinking budget " + budget + " of level " + level + " must stay below the output limit "
                        + maxTokens
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
