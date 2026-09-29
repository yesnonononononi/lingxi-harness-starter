package com.summit.core.json;

import com.fasterxml.jackson.core.json.JsonReadFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.json.JsonMapper;

/** Lenient JSON reader shared by every call site that has to parse a model's raw output (context-compaction summary, application-supplied state, ...). */
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

    /** Reads the first JSON object of the given text: the balanced object span is extracted first (tolerating fences and surrounding prose), and the whole text is tried as a fallback when no object span is found. */
    public static JsonNode readFirstObject(String raw) {
        JsonNode node = readTree(extractJsonObject(raw));
        return node == null ? readTree(raw) : node;
    }

    /** Parses the given text with the lenient mapper. */
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

    /** Extracts the first balanced JSON object from arbitrary text. */
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
