package com.summit.core.model.image;

import lombok.Builder;
import lombok.extern.jackson.Jacksonized;

/** Per-call image specifications. Null fields inherit the selected model's defaults. */
@Builder(toBuilder = true)
@Jacksonized
public record ImageGenerationOptions(String size, String quality, Integer count, String background, String outputFormat) {
    public static final ImageGenerationOptions EMPTY = new ImageGenerationOptions(null, null, null);

    /** Preserves existing callers and persisted requests without output overrides. */
    public ImageGenerationOptions(String size, String quality, Integer count) {
        this(size, quality, count, null, null);
    }

    public ImageGenerationOptions {
        if (size != null && size.isBlank()) throw new IllegalArgumentException("Image size must be non-blank");
        if (quality != null && quality.isBlank()) throw new IllegalArgumentException("Image quality must be non-blank");
        if (count != null && count <= 0) throw new IllegalArgumentException("Image count must be positive");
        if (background != null && background.isBlank()) throw new IllegalArgumentException("Image background must be non-blank");
        if (outputFormat != null && outputFormat.isBlank()) throw new IllegalArgumentException("Image output format must be non-blank");
    }

    /** Resolves fields independently; absent size/quality retain the provider's own defaults. */
    public ImageGenerationOptions withDefaults(ImageGenerationOptions defaults) {
        if (defaults == null) defaults = EMPTY;
        return new ImageGenerationOptions(size != null ? size : defaults.size,
                quality != null ? quality : defaults.quality,
                count != null ? count : defaults.count != null ? defaults.count : 1,
                background != null ? background : defaults.background,
                outputFormat != null ? outputFormat : defaults.outputFormat);
    }
}
