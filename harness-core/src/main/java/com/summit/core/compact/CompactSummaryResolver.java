package com.summit.core.compact;

import com.fasterxml.jackson.core.json.JsonReadFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.json.JsonMapper;
import lombok.extern.slf4j.Slf4j;

import java.util.ArrayList;
import java.util.List;

/**
 * Parses the raw text returned by the context-compaction model into a {@link ContextSummary}.
 *
 * <p><b>Problem.</b> The compaction model is a plain LLM, so its output is not always strictly
 * valid JSON. In practice the model frequently emits:
 * <ul>
 *   <li>unescaped control characters inside strings (raw newlines / tabs) — previously this made
 *       Jackson throw {@code Illegal unquoted character (CTRL-CHAR, code 10)} in
 *       {@code RuntimeProcessorTemplate#resolveContextSummary}, which then returned {@code null}
 *       and caused {@code DefaultConversationManager#rebuildContext} to silently skip the rebuild.
 *       The agent loop kept invoking {@code compact_context} without the context ever shrinking,
 *       retrying (and paying tokens) until the model happened to produce parseable output;</li>
 *   <li>JSON wrapped in {@code ```json} fences or surrounded by explanatory prose;</li>
 *   <li>minor quirks such as single-quoted strings, unquoted field names, or trailing commas;</li>
 *   <li>legacy keys like {@code completed[]} / {@code pending[]} that an older prompt instructed
 *       the model to use, instead of the real {@code completed} / {@code pending} fields.</li>
 * </ul>
 *
 * <p><b>Solution.</b> Instead of strict parsing:
 * <ol>
 *   <li>locate the first balanced JSON object (string/escape aware) and extract it, tolerating
 *       markdown fences and surrounding prose;</li>
 *   <li>parse the extracted span with a lenient {@link JsonMapper} that allows unescaped control
 *       characters, single quotes, unquoted field names, trailing commas and comments;</li>
 *   <li>normalize known key aliases ({@code completed[]}/{@code pending[]} etc.) and the
 *       {@code state} value;</li>
 *   <li>if the model did not return a JSON object at all (e.g. a plain-text summary), fall back to
 *       using the raw text as the summary so that {@code rebuildContext} still runs and the agent
 *       never gets stuck in a compaction loop.</li>
 * </ol>
 */
@Slf4j
public final class CompactSummaryResolver {

    private CompactSummaryResolver() {
    }

    /** Lenient mapper: tolerates unescaped control chars / single quotes / unquoted fields / trailing commas / comments. */
    private static final JsonMapper LENIENT_MAPPER = JsonMapper.builder()
            .enable(JsonReadFeature.ALLOW_UNESCAPED_CONTROL_CHARS)
            .enable(JsonReadFeature.ALLOW_SINGLE_QUOTES)
            .enable(JsonReadFeature.ALLOW_UNQUOTED_FIELD_NAMES)
            .enable(JsonReadFeature.ALLOW_TRAILING_COMMA)
            .enable(JsonReadFeature.ALLOW_JAVA_COMMENTS)
            .build();

    /** Upper bound of the fallback summary text (prevents dumping an over-long raw output into the rebuilt session). */
    private static final int MAX_FALLBACK_LENGTH = 20_000;

    /**
     * Resolves the raw compaction-model output into a {@link ContextSummary}.
     *
     * @param rawOutput raw text returned by the compaction model
     * @return the parsed summary; {@code null} when the input is blank or otherwise unusable
     *         (callers should then skip the context rebuild)
     */
    public static ContextSummary resolve(String rawOutput) {
        if (rawOutput == null || rawOutput.isBlank()) {
            log.warn("【context-summary】compact model returned blank output, skip context rebuild");
            return null;
        }
        String trimmed = rawOutput.trim();

        // 1) Extract the balanced JSON object span (tolerates ```json fences and surrounding prose)
        JsonNode node = tryParse(extractJsonObject(trimmed));
        // 2) Fall back to parsing the whole text as-is
        if (node == null) {
            node = tryParse(trimmed);
        }
        if (node != null && node.isObject()) {
            return toSummary(node, trimmed);
        }

        // 3) The model produced no JSON object: use the raw text as the summary so compaction can still proceed
        log.warn("【context-summary】compact model output is not a JSON object, falling back to raw text as summary");
        return ContextSummary.builder()
                .goal("context compaction")
                .summary(truncate(trimmed))
                .completed(List.of())
                .pending(List.of())
                .state("DONE")
                .build();
    }

    // ------------------------------------------------------------------
    // helpers
    // ------------------------------------------------------------------

    /** Tries to parse the given text with the lenient mapper; returns {@code null} on failure. */
    private static JsonNode tryParse(String text) {
        if (text == null || text.isBlank()) {
            return null;
        }
        try {
            return LENIENT_MAPPER.readTree(text);
        } catch (Exception e) {
            return null;
        }
    }

    /**
     * Extracts the first balanced JSON object from arbitrary text.
     * The scan is string-aware (double quotes and escapes), so braces inside a string value
     * do not break brace matching.
     */
    private static String extractJsonObject(String raw) {
        int start = raw.indexOf('{');
        if (start < 0) {
            return null;
        }
        int depth = 0;
        boolean inString = false;
        boolean escaped = false;
        for (int i = start; i < raw.length(); i++) {
            char c = raw.charAt(i);
            if (inString) {
                if (escaped) {
                    escaped = false;
                } else if (c == '\\') {
                    escaped = true;
                } else if (c == '"') {
                    inString = false;
                }
                continue;
            }
            if (c == '"') {
                inString = true;
            } else if (c == '{') {
                depth++;
            } else if (c == '}' && --depth == 0) {
                return raw.substring(start, i + 1);
            }
        }
        return null;
    }

    private static ContextSummary toSummary(JsonNode obj, String rawFallback) {
        String summary = text(obj, "summary");
        if (summary == null || summary.isBlank()) {
            summary = truncate(rawFallback);
        }
        return ContextSummary.builder()
                .goal(text(obj, "goal"))
                .summary(summary)
                .completed(textList(obj, "completed", "completed[]", "completed_tasks", "tasks_completed"))
                .pending(textList(obj, "pending", "pending[]", "pending_tasks", "tasks_pending"))
                .state(normalizeState(text(obj, "state")))
                .build();
    }

    private static JsonNode field(JsonNode obj, String... names) {
        for (String name : names) {
            JsonNode node = obj.get(name);
            if (node != null && !node.isMissingNode() && !node.isNull()) {
                return node;
            }
        }
        return null;
    }

    private static String text(JsonNode obj, String... names) {
        JsonNode node = field(obj, names);
        if (node == null) {
            return null;
        }
        return node.isTextual() ? node.asText() : node.toString();
    }

    private static List<String> textList(JsonNode obj, String... names) {
        JsonNode node = field(obj, names);
        List<String> result = new ArrayList<>();
        if (node == null) {
            return result;
        }
        if (node.isArray()) {
            for (JsonNode element : node) {
                if (element.isTextual() && !element.asText().isBlank()) {
                    result.add(element.asText());
                }
            }
        } else if (node.isTextual() && !node.asText().isBlank()) {
            result.add(node.asText());
        }
        return result;
    }

    private static String normalizeState(String state) {
        if (state == null) {
            return null;
        }
        String upper = state.trim().toUpperCase();
        return ("DONE".equals(upper) || "FAILED".equals(upper)) ? upper : null;
    }

    private static String truncate(String text) {
        if (text == null) {
            return null;
        }
        return text.length() > MAX_FALLBACK_LENGTH ? text.substring(0, MAX_FALLBACK_LENGTH) : text;
    }
}
