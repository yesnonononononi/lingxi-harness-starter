package com.summit.runtime.model;

import com.summit.core.conf.ModelConfig;
import com.summit.core.exception.NoSuchModelProviderException;
import com.summit.core.model.ModelInvoker;
import com.summit.core.model.ModelProviderRegistry;
import com.summit.core.model.RequestModelInvokerFactory;
import com.summit.core.model.chat.ChatModel;
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
    // Keep a private snapshot: callers and providers may mutate their own ModelConfig instances.
    private record ConfigKey(ModelConfig snapshot) {}

    private final String defaultProvider;
    private final ModelConfig modelConfig;
    private final ModelProviderRegistry<ChatModel> chatProviders;
    private final ModelProviderRegistry<StreamingChatModel> streamingProviders;
    private final Map<Object, Selection> selections = new ConcurrentHashMap<>();

    public DefaultRequestModelInvokerFactory(String defaultProvider, ModelConfig modelConfig,
                                             ModelProviderRegistry<ChatModel> chatProviders,
                                             ModelProviderRegistry<StreamingChatModel> streamingProviders) {
        this.defaultProvider = defaultProvider == null || defaultProvider.isBlank()
                ? "default" : defaultProvider.trim();
        this.modelConfig = modelConfig;
        this.chatProviders = chatProviders;
        this.streamingProviders = streamingProviders;
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
        ConfigKey key = new ConfigKey(config.toBuilder().provider(name).build());
        return selections.computeIfAbsent(key,
                ignored -> getSelection(name, key.snapshot().toBuilder().build()));
    }

    private Selection createSelection(String name) {
        return getSelection(name, this.modelConfig.toBuilder().provider(name).build());
    }

    private String name(String providerName){
       return providerName == null || providerName.isBlank()
                ? defaultProvider : providerName.trim();
    }

    private RequestModelInvokerFactory.Selection getSelection(String name, ModelConfig modelConfig) {
        boolean chat = chatProviders.contains(name);
        boolean streaming = streamingProviders.contains(name);
        if (chat && streaming) {
            throw new IllegalArgumentException("Ambiguous model provider registered for chat and streaming: " + name);
        }
        if (streaming) {
            StreamingChatModel model = streamingProviders.create(modelConfig);
            return new Selection(name, new DefaultStreamingModelInvoker(model), true);
        }
        if (chat) {
            ChatModel model = chatProviders.create(modelConfig);
            ModelInvoker invoker = command -> model.chat(command.chatRequest());
            return new Selection(name, invoker, false);
        }
        throw new NoSuchModelProviderException("No such model provider: " + name);
    }

}
