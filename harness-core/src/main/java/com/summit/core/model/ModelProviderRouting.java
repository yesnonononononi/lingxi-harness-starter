package com.summit.core.model;

import java.util.Locale;

/** Explicit providers take precedence; model names supply the protocol when none was selected. */
public final class ModelProviderRouting {
    private ModelProviderRouting() {}

    public static String chatProvider(String provider, String modelName, boolean streaming) {
        if (provider != null && !provider.isBlank()) return provider.trim();
        if (isAnthropic(modelName)) {
            return streaming ? DefaultModelProviderNames.ANTHROPIC_STREAMING : DefaultModelProviderNames.ANTHROPIC;
        }
        return streaming ? DefaultModelProviderNames.DEFAULT_STREAMING : DefaultModelProviderNames.DEFAULT;
    }

    public static String compactProvider(String provider, String modelName) {
        if (provider != null && !provider.isBlank()) return provider.trim();
        return isAnthropic(modelName) ? DefaultModelProviderNames.ANTHROPIC_COMPACT : "default-compact";
    }

    private static boolean isAnthropic(String modelName) {
        return modelName != null && (modelName.strip().toLowerCase(Locale.ROOT).startsWith("claude-") || modelName.contains("claude"));
    }
}
