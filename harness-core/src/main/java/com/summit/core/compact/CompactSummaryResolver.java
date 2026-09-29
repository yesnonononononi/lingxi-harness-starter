package com.summit.core.compact;

import com.fasterxml.jackson.databind.JsonNode;
import com.summit.core.json.LenientJsonReader;
import lombok.extern.slf4j.Slf4j;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Parses the raw text returned by the context-compaction model into a {@link ContextSummary}.
 *
 * <p><b>Problem.</b> The compaction model is a plain LLM, so its output is not always strictly
 * valid JSON. In practice the model frequently emits:
 * <ul>
 *   <li>unescaped control characters inside strings (raw newlines / tabs) — previously this made
 *       Jackson throw {@code Illegal unquoted character (CTRL-CHAR, code 10)}, which led to a
 *       {@code null} summary and caused {@code DefaultConversationManager#rebuildContext} to
 *       silently skip the rebuild.
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
 *   <li>delegate the tolerant read to {@link LenientJsonReader}, which locates the first balanced
 *       JSON object (string/escape aware, tolerating markdown fences and surrounding prose) and
 *       parses it with a lenient mapper that allows unescaped control characters, single quotes,
 *       unquoted field names, trailing commas and comments;</li>
 *   <li>normalize known key aliases ({@code completed[]}/{@code pending[]} etc.) and the
 *       {@code state} value;</li>
 *   <li>if the model did not return a JSON object at all (e.g. a plain-text summary), fall back to
 *       using the raw text as the summary so that {@code rebuildContext} still runs and the agent
 *       never gets stuck in a compaction loop;</li>
 *   <li>never let an unexpected failure escape: every read is best-effort, and a failure degrades to
 *       the raw-text summary instead of to {@code null}. A {@code null} summary skips the rebuild,
 *       the oversized context stays as it is and the model asks for another compaction — the loop
 *       this class exists to break.</li>
 * </ol>
 */
@Slf4j
public final class CompactSummaryResolver {

    private CompactSummaryResolver() {
    }

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
        String trimmed = stripBom(rawOutput.trim());

        // Tolerant read: the first balanced JSON object (tolerating ```json fences and prose),
        // with the whole text as a fallback.
        ContextSummary parsed = object(trimmed);
        if (parsed == null) {
            // Second chance only: models use typographic quotes as string delimiters, but the very
            // same characters legitimately occur inside the summary text — normalizing up-front
            // would inject unbalanced quotes into an otherwise perfectly valid JSON.
            String unquoted = normalizeQuotes(trimmed);
            if (!unquoted.equals(trimmed)) {
                parsed = object(unquoted);
            }
        }
        if (parsed != null) {
            return parsed;
        }

        // No parseable JSON object (typically an output truncated by a token budget, or an object
        // whose braces are unbalanced): salvage whatever structured fields are still readable.
        //
        // A salvage failure must never escape: the caller would then fall back to "no summary",
        // the context would keep its full size and the agent loop would call compact_context again
        // and again — the compaction loop this resolver exists to prevent.
        try {
            ContextSummary salvaged = salvage(trimmed);
            if (salvaged != null) {
                log.info("【context-summary】compact model output is not a parseable JSON object, salvaged goal/summary fields "
                        + "from the raw text");
                return salvaged;
            }
        } catch (Exception unexpected) {
            log.warn("【context-summary】failed to salvage the summary fields of the compact output, "
                    + "falling back to the raw text", unexpected);
        }

        // 3) The model produced no JSON object: use the raw text as the summary so compaction can still proceed
        log.warn("【context-summary】compact model output is not a JSON object, falling back to raw text as summary: {}",
                preview(trimmed));
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

    /** Maximum length of the raw output preview written to the log. */
    private static final int PREVIEW_LENGTH = 200;

    /** Parses the first JSON object of the given text; {@code null} when the text holds none. */
    private static ContextSummary object(String text) {
        JsonNode node = LenientJsonReader.readFirstObject(text);
        return node != null && node.isObject() ? toSummary(node, text) : null;
    }

    /** Drops the byte-order mark some models prepend: it breaks both the brace scanner and Jackson. */
    private static String stripBom(String text) {
        return text == null ? null : text.replace('\uFEFF', ' ').trim();
    }

    /**
     * Turns the typographic quotes a model may use as string delimiters (“ ” ‘ ’) into ASCII ones.
     *
     * <p>Only ever applied as a second chance, after the verbatim read failed: the same characters
     * regularly occur <em>inside</em> the summary text (quoted task names, ...), and replacing them
     * there injects unbalanced quotes into an otherwise valid JSON.</p>
     */
    private static String normalizeQuotes(String text) {
        if (text == null) {
            return null;
        }
        return text.replace('“', '"')
                .replace('”', '"')
                .replace('„', '"')
                .replace('″', '"')
                .replace('‘', '\'')
                .replace('’', '\'')
                .trim();
    }

    /**
     * Reads the summary fields straight out of the raw text with lenient, key-based patterns.
     * Used when the output holds no balanced JSON object at all (truncated output, prose
     * around the fields, ...) so the structured parts are still preserved instead of dumping
     * the whole raw text into the rebuilt context.
     *
     * @return the salvaged summary, or {@code null} when no known field could be read
     */
    private static ContextSummary salvage(String text) {
        String goal = firstQuotedField(text, "goal");
        String summary = firstQuotedField(text, "summary", "summary_text", "content");
        if (goal == null && summary == null) {
            return null;
        }
        return ContextSummary.builder()
                .goal(goal)
                .summary(summary == null ? truncate(text) : summary)
                .completed(listField(text, "completed", "completed[]", "completed_tasks"))
                .pending(listField(text, "pending", "pending[]", "pending_tasks"))
                .state(normalizeState(firstQuotedField(text, "state")))
                .build();
    }

    /** First {@code "key": "value"} occurrence of any of the given keys, unescaped. */
    private static String firstQuotedField(String text, String... names) {
        for (String name : names) {
            // Pattern.quote: key aliases such as "completed[]" carry regex metacharacters.
            Matcher matcher = Pattern.compile("\"" + Pattern.quote(name) + "\"\\s*:\\s*\"((?:[^\"\\\\]|\\\\.)*)\"")
                    .matcher(text);
            if (matcher.find()) {
                return unescape(matcher.group(1));
            }
        }
        return null;
    }

    /** First {@code "key": [...]} occurrence of any of the given keys; both quoted and bare items are read. */
    private static List<String> listField(String text, String... names) {
        List<String> result = new ArrayList<>();
        for (String name : names) {
            // Pattern.quote: key aliases such as "completed[]" carry regex metacharacters.
            Matcher array = Pattern.compile("\"" + Pattern.quote(name) + "\"\\s*:\\s*\\[([^]]*)").matcher(text);
            if (!array.find()) {
                continue;
            }
            Matcher item = Pattern.compile("\"((?:[^\"\\\\]|\\\\.)*)\"").matcher(array.group(1));
            while (item.find()) {
                String value = unescape(item.group(1));
                if (!value.isBlank()) {
                    result.add(value);
                }
            }
            return result;
        }
        return result;
    }

    /** Resolves the escapes a model writes inside JSON string values (\\n, \\t, \\", \\\\). */
    private static String unescape(String value) {
        if (value == null || value.indexOf('\\') < 0) {
            return value;
        }
        StringBuilder out = new StringBuilder(value.length());
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            if (c != '\\' || i + 1 >= value.length()) {
                out.append(c);
                continue;
            }
            char next = value.charAt(++i);
            switch (next) {
                case 'n' -> out.append('\n');
                case 't' -> out.append('\t');
                case 'r' -> out.append('\r');
                default -> out.append(next);
            }
        }
        return out.toString();
    }

    private static String preview(String text) {
        if (text == null) {
            return null;
        }
        String flattened = text.replaceAll("\\s+", " ");
        return flattened.length() > PREVIEW_LENGTH ? flattened.substring(0, PREVIEW_LENGTH) + "..." : flattened;
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
