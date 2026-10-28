package com.summit.core.memory;

import java.util.Objects;

/**
 * A rejection has save=false and no replacement. Approval with a null replacement saves the
 * proposal unchanged; approval with a replacement saves that text, including an empty string.
 */
public record MemoryDecision(
        String newMemoryStr,
        boolean save
) {
    public MemoryDecision {
        validate(newMemoryStr, save);
    }

    public static MemoryDecision approve() {
        return new MemoryDecision(null, true);
    }

    public static MemoryDecision reject() {
        return new MemoryDecision(null, false);
    }

    public static MemoryDecision replace(String content) {
        return new MemoryDecision(Objects.requireNonNull(content, "content"), true);
    }

    public void requireValid() {
        validate(newMemoryStr, save);
    }

    private static void validate(String content, boolean save) {
        if (!save && content != null) {
            throw new IllegalArgumentException("A rejected memory change cannot carry replacement content");
        }
    }
}
