package com.summit.core.model;

/** Names of the model providers shipped with the framework. */
public final class DefaultModelProviderNames {

    /** OpenAI-protocol chat provider ({@code OpenAiChatModelProvider}). */
    public static final String DEFAULT = "default";

    /** OpenAI-protocol streaming provider ({@code OpenAiStreamingModelProvider}). */
    public static final String DEFAULT_STREAMING = "default-streaming";

    private DefaultModelProviderNames() {
    }
}
