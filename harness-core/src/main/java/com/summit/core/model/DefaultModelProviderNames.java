package com.summit.core.model;

/** Names of the model providers shipped with the framework. */
public final class DefaultModelProviderNames {

    /** OpenAI-protocol chat provider. */
    public static final String DEFAULT = "default";

    /** OpenAI-protocol streaming provider. */
    public static final String DEFAULT_STREAMING = "default-streaming";

    public static final String DEFAULT_COMPACT = "default-compact";
    public static final String DEFAULT_IMAGE = "default-image-model";

    public static final String NET_CHAT = "net-chat";
    public static final String NET_STREAMING = "net-streaming";

    public static final String ANTHROPIC = "anthropic";
    public static final String ANTHROPIC_STREAMING = "anthropic-streaming";
    public static final String ANTHROPIC_COMPACT = "anthropic-compact";

    private DefaultModelProviderNames() {
    }
}
