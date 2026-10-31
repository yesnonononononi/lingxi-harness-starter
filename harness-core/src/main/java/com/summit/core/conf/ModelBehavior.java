package com.summit.core.conf;

import java.util.Objects;

/**
 * How one request asks the model, as opposed to where it reaches it.
 *
 * <p>{@link ModelConfig} carries both, which made "reuse this connection for another kind of work"
 * look like "reuse this configuration": context compaction inherited the chat reasoning effort and
 * thinking flags and spent the summary's output budget on thinking. Call semantics live here so a
 * caller can keep a connection and swap only the behavior — see
 * {@code RequestCompactModelResolver}, which applies the compact profile to the chat connection.</p>
 *
 * <p>Image options stay on {@link ModelConfig}: they describe image generation, which no text
 * request (chat or summary) ever consults.</p>
 */
public record ModelBehavior(
        Integer maxTokens,
        String reasoningEffort,
        boolean returnThinking,
        boolean sendThinking,
        ModelConfig.AnthropicOptions anthropicOptions) {

    /** The call semantics a configuration currently carries. */
    public static ModelBehavior of(ModelConfig config) {
        Objects.requireNonNull(config, "model config");
        return new ModelBehavior(config.getMaxTokens(), config.getReasoningEffort(),
                config.isReturnThinking(), config.isSendThinking(), config.getAnthropicOptions());
    }

    /** Copies {@code connection} with this behavior; endpoint, credentials and retry settings stay untouched. */
    public ModelConfig applyTo(ModelConfig connection) {
        Objects.requireNonNull(connection, "model connection");
        return connection.toBuilder()
                .maxTokens(maxTokens)
                .reasoningEffort(reasoningEffort)
                .returnThinking(returnThinking)
                .sendThinking(sendThinking)
                .anthropicOptions(anthropicOptions)
                .build();
    }
}
