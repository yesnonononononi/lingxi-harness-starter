package com.summit.runtime.model;

import com.summit.core.conversation.event.RuntimeEventPublisher;
import com.summit.core.exception.NoSuchModelProviderException;
import com.summit.core.model.chat.ChatModel;
import com.summit.core.conf.ModelConfig;
import com.summit.core.model.ModelInvoker;
import com.summit.core.model.ModelProviderRegistry;
import com.summit.core.model.RequestModelInvokerFactory;
import com.summit.core.model.streaming.StreamingChatModel;

import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Selects and lazily creates provider strategies.
 *
 * <p>Selections are cached so that repeated requests reuse the same underlying model. The cache key
 * reflects what actually shapes the model instance:</p>
 * <ul>
 *   <li>{@link #select(String)} — keyed by provider name, and the model is built from the default
 *       {@link ModelConfig} supplied by the application.</li>
 *   <li>{@link #select(ModelConfig)} — keyed by the full request-level configuration fingerprint, so
 *       two requests carrying different {@code ModelConfig} values (even under the same provider
 *       name) each get their own model instance.</li>
 * </ul>
 */
public final class DefaultRequestModelInvokerFactory implements RequestModelInvokerFactory {
    private static final String PROVIDER_KEY_PREFIX = "provider:";
    private static final String CONFIG_KEY_PREFIX = "config:";
    private static final char KEY_SEPARATOR = '|';

    private final String defaultProvider;
    private final ModelConfig modelConfig;
    private final ModelProviderRegistry<ChatModel> chatProviders;
    private final ModelProviderRegistry<StreamingChatModel> streamingProviders;
    private final RuntimeEventPublisher eventPublisher;
    private final Map<String, Selection> selections = new ConcurrentHashMap<>();

    public DefaultRequestModelInvokerFactory(String defaultProvider, ModelConfig modelConfig,
                                             ModelProviderRegistry<ChatModel> chatProviders,
                                             ModelProviderRegistry<StreamingChatModel> streamingProviders,
                                             RuntimeEventPublisher eventPublisher) {
        this.defaultProvider = defaultProvider == null || defaultProvider.isBlank()
                ? "default" : defaultProvider.trim();
        this.modelConfig = modelConfig;
        this.chatProviders = chatProviders;
        this.streamingProviders = streamingProviders;
        this.eventPublisher = eventPublisher;
    }

    @Override
    public Selection select(String providerName) {
        String name = name(providerName);
        return selections.computeIfAbsent(PROVIDER_KEY_PREFIX + name, key -> createSelection(name));
    }

    @Override
    public Selection select(ModelConfig config) {
        Objects.requireNonNull(config, "model config");
        String name = name(config.getProvider());
        return selections.computeIfAbsent(
                CONFIG_KEY_PREFIX + fingerprint(name, config),
                key -> getSelection(name, withProvider(config, name)));
    }

    private Selection createSelection(String name) {
        return getSelection(name, withProvider(this.modelConfig, name));
    }

    private String name(String providerName){
       return providerName == null || providerName.isBlank()
                ? defaultProvider : providerName.trim();
    }

    /**
     * Builds a cache key covering every field that participates in creating a model instance.
     */
    private String fingerprint(String providerName, ModelConfig config) {
        return providerName + KEY_SEPARATOR +
                config.getBaseUrl() + KEY_SEPARATOR +
                config.getModelName() + KEY_SEPARATOR +
                config.getApiKey() + KEY_SEPARATOR +
                config.getTimeout() + KEY_SEPARATOR +
                config.getMaxTokens() + KEY_SEPARATOR +
                config.getReasoningEffort() + KEY_SEPARATOR +
                config.isReturnThinking() + KEY_SEPARATOR +
                config.isSendThinking();
    }

    private RequestModelInvokerFactory.Selection getSelection(String name, ModelConfig modelConfig) {
        boolean chat = chatProviders.contains(name);
        boolean streaming = streamingProviders.contains(name);
        if (chat && streaming) {
            throw new IllegalArgumentException("Ambiguous model provider registered for chat and streaming: " + name);
        }
        if (streaming) {
            StreamingChatModel model = streamingProviders.create(modelConfig);
            return new Selection(name, new DefaultStreamingModelInvoker(model, eventPublisher), true);
        }
        if (chat) {
            ChatModel model = chatProviders.create(modelConfig);
            ModelInvoker invoker = command -> model.chat(command.chatRequest());
            return new Selection(name, invoker, false);
        }
        throw new NoSuchModelProviderException("No such model provider: " + name);
    }

    /**
     * Returns a defensive copy of {@code source} with the resolved provider name applied. The global
     * application-level config is never mutated, so concurrent requests stay isolated.
     */
    private ModelConfig withProvider(ModelConfig source, String provider) {
        return ModelConfig.builder()
                .baseUrl(source.getBaseUrl())
                .apiKey(source.getApiKey())
                .modelName(source.getModelName())
                .provider(provider)
                .timeout(source.getTimeout())
                .maxTokens(source.getMaxTokens())
                .reasoningEffort(source.getReasoningEffort())
                .returnThinking(source.isReturnThinking())
                .sendThinking(source.isSendThinking())
                .build();
    }
}
