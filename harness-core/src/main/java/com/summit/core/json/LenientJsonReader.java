package com.summit.core.json;

import com.fasterxml.jackson.core.json.JsonReadFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.json.JsonMapper;

/**
 * Lenient JSON reader shared by every call site that has to parse a model's raw output
 * (context-compaction summary, plan text, ...).
 *
 * <p>LLM output is rarely strictly valid JSON: it is often wrapped in {@code ```json} fences
 * or surrounded by prose, and may contain unescaped control characters, single-quoted strings,
 * unquoted field names, trailing commas or comments. Instead of strict parsing this reader
 * locates the first balanced JSON object (string/escape aware) and parses it with a lenient
 * mapper, so such quirks do not silently turn a usable answer into {@code null}.</p>
 */
public final class LenientJsonReader {

    /** Lenient mapper: tolerates unescaped control chars / single quotes / unquoted fields / trailing commas / comments. */
    private static final JsonMapper LENIENT_MAPPER = JsonMapper.builder()
            .enable(JsonReadFeature.ALLOW_UNESCAPED_CONTROL_CHARS)
            .enable(JsonReadFeature.ALLOW_SINGLE_QUOTES)
            .enable(JsonReadFeature.ALLOW_UNQUOTED_FIELD_NAMES)
            .enable(JsonReadFeature.ALLOW_TRAILING_COMMA)
            .enable(JsonReadFeature.ALLOW_JAVA_COMMENTS)
            .build();

    private LenientJsonReader() {
    }

    /**
     * Reads the first JSON object of the given text: the balanced object span is extracted
     * first (tolerating fences and surrounding prose), and the whole text is tried as a
     * fallback when no object span is found.
     *
     * @param raw raw model output
     * @return the parsed node, or {@code null} when the text holds no parseable JSON
     */
    public static JsonNode readFirstObject(String raw) {
        JsonNode node = readTree(extractJsonObject(raw));
        return node == null ? readTree(raw) : node;
    }

    /**
     * Parses the given text with the lenient mapper.
     *
     * @param raw text to parse
     * @return the parsed node, or {@code null} when the text is blank or unparseable
     */
    public static JsonNode readTree(String raw) {
        if (raw == null || raw.isBlank()) {
            return null;
        }
        try {
            return LENIENT_MAPPER.readTree(raw);
        } catch (Exception e) {
            return null;
        }
    }

    /**
     * Extracts the first balanced JSON object from arbitrary text.
     * The scan is string-aware (double quotes and escapes), so braces inside a string value
     * do not break brace matching.
     *
     * @param raw arbitrary text
     * @return the object span including its braces, or {@code null} when none is found
     */
    public static String extractJsonObject(String raw) {
        if (raw == null || raw.isBlank()) {
            return null;
        }
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
}
