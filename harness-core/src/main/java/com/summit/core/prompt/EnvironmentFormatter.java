package com.summit.core.prompt;

import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.TreeMap;

/**
 * Formats workspace environment variables for model prompts, redacting secret-looking keys
 * and bounding output size.
 */
public final class EnvironmentFormatter {
    private static final int MAX_ENV_ENTRIES = 40;
    private static final int MAX_ENV_VALUE_LENGTH = 200;
    private static final List<String> SENSITIVE_KEY_MARKERS =
            List.of("KEY", "TOKEN", "SECRET", "PASSWORD", "PASSWD", "CREDENTIAL", "PRIVATE");

    private EnvironmentFormatter() {
    }

    /** Formats sorted environment entries as bullet lines, or {@code null} if nothing non-blank is present. */
    public static String formatEntries(Map<String, String> envs, String indent) {
        if (envs == null || envs.isEmpty()) {
            return null;
        }
        Map<String, String> sorted = new TreeMap<>(String.CASE_INSENSITIVE_ORDER);
        envs.forEach((key, value) -> {
            if (key != null && !key.isBlank()) {
                sorted.put(key, value);
            }
        });
        if (sorted.isEmpty()) {
            return null;
        }
        StringBuilder out = new StringBuilder();
        int printed = 0;
        for (Map.Entry<String, String> entry : sorted.entrySet()) {
            if (printed > 0) {
                out.append('\n');
            }
            if (printed >= MAX_ENV_ENTRIES) {
                out.append(indent).append("- ... (").append(sorted.size() - printed).append(" more)");
                break;
            }
            out.append(indent).append("- ").append(entry.getKey()).append('=')
                    .append(redactOrTruncate(entry.getKey(), entry.getValue()));
            printed++;
        }
        return out.toString();
    }

    private static String redactOrTruncate(String key, String value) {
        String upper = key.toUpperCase(Locale.ROOT);
        if (SENSITIVE_KEY_MARKERS.stream().anyMatch(upper::contains)) {
            return "<redacted>";
        }
        String text = value == null ? "" : value;
        return text.length() <= MAX_ENV_VALUE_LENGTH
                ? text : text.substring(0, MAX_ENV_VALUE_LENGTH) + "... (truncated)";
    }
}
