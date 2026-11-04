package com.summit.runtime.compact;

import com.summit.core.agent.AgentRequest;
import com.summit.core.conf.ModelBehavior;
import com.summit.core.conf.ModelConfig;
import com.summit.core.conf.ModelRetryConfig;
import com.summit.core.conf.SensitiveDataProvider;
import com.summit.core.conf.SensitiveDataSpec;
import com.summit.core.model.DefaultModelProviderNames;
import com.summit.core.model.ModelChatCommand;
import com.summit.core.model.ModelProviderRegistry;
import com.summit.core.model.ModelProviderRouting;
import com.summit.core.model.chat.ChatModel;
import com.summit.runtime.model.DefaultChatModelInvoker;

import java.time.Duration;
import java.util.Objects;

/** Resolves exact request revisions without retaining credentials in executions or shared mutable state. */
public final class RequestCompactModelResolver {
    private final ModelConfig applicationConfig;
    /** Call semantics of summarization; never the chat request's effort or thinking flags. */
    private final ModelBehavior compactBehavior;
    private final SensitiveDataProvider<ModelConfig> configurations;
    private final ModelProviderRegistry<ChatModel> providers;
    private final ChatModel applicationCompactModel;

    public RequestCompactModelResolver(ModelConfig applicationConfig, ModelBehavior compactBehavior,
                                       SensitiveDataProvider<ModelConfig> configurations,
                                       ModelProviderRegistry<ChatModel> providers, ChatModel applicationCompactModel) {
        this.applicationConfig = applicationConfig;
        this.compactBehavior = Objects.requireNonNull(compactBehavior, "compact behavior");
        this.configurations = configurations;
        this.providers = providers;
        this.applicationCompactModel = applicationCompactModel;
    }

    public ChatModel resolve(AgentRequest request) {
        Objects.requireNonNull(request, "Agent request is required for context compaction");

        ModelConfig config = applicationConfig;

        SensitiveDataSpec spec = request.getModelConfigSpec();
        if (spec != null) {

            if (configurations == null) {
                throw new IllegalStateException("A model configuration reference requires a SensitiveDataProvider<ModelConfig>");
            }
            config = configurations.get(spec.getId(), spec.getVersion());
            if (config == null) {
                throw new IllegalStateException("Model configuration revision is unavailable: id="
                        + spec.getId() + ", version=" + spec.getVersion());
            }

        } else if (config != null && request.getModelProvider() != null && !request.getModelProvider().isBlank()) {
            config = config.toBuilder()
                    .provider(request.getModelProvider()
                    .trim())
                    .build();
        }
        if (config == null) throw new IllegalStateException("Model configuration is required for context compaction");

        ModelConfig selected;

        if (config.isReuseModelForCompaction()) {

            String provider = ModelProviderRouting.chatProvider(config.getProvider(), config.getModelName(), false);

            // Summaries are synchronous even when the main request selected a streaming provider.
            provider = switch (provider) {
                case DefaultModelProviderNames.DEFAULT, DefaultModelProviderNames.DEFAULT_STREAMING ->
                        providers.contains(DefaultModelProviderNames.DEFAULT_COMPACT)
                                ? DefaultModelProviderNames.DEFAULT_COMPACT : DefaultModelProviderNames.DEFAULT;
                case DefaultModelProviderNames.NET_STREAMING -> DefaultModelProviderNames.NET_CHAT;
                case DefaultModelProviderNames.ANTHROPIC_STREAMING -> DefaultModelProviderNames.ANTHROPIC;
                default -> provider;
            };
            // Connection from the request revision, call semantics from the compact profile: a summary
            // that inherits chat effort or thinking spends its own output budget on reasoning.
            selected = compactBehavior.applyTo(config.toBuilder().provider(provider).build());

        } else if (config.getCompactModel() != null) {
            ModelConfig.CompactModelConfig compact = config.getCompactModel();

            if (compact.baseUrl() == null || compact.baseUrl().isBlank()
                    || compact.apiKey() == null || compact.apiKey().isBlank()
                    || compact.modelName() == null || compact.modelName().isBlank()) {
                throw new IllegalArgumentException("Compact model baseUrl, apiKey and modelName must all be non-blank");
            }

            selected = compactBehavior.applyTo(ModelConfig.builder()
                    .baseUrl(compact.baseUrl())
                    .apiKey(compact.apiKey())
                    .modelName(compact.modelName())
                    .provider(ModelProviderRouting.compactProvider(null, compact.modelName()))
                    .timeout(config.getTimeout())
                    .maxRetries(config.getMaxRetries())
                    .build());

        } else {
            return applicationCompactModel;
        }
        return createCompactModel(providers, selected);
    }

    /** Applies the compact call's retry budget at the invocation boundary. */
    public static ChatModel createCompactModel(ModelProviderRegistry<ChatModel> providers, ModelConfig config) {
        String provider = config.getProvider().trim();
        boolean net = switch (provider) {
            case DefaultModelProviderNames.DEFAULT, DefaultModelProviderNames.DEFAULT_COMPACT,
                    DefaultModelProviderNames.NET_CHAT, DefaultModelProviderNames.ANTHROPIC,
                    DefaultModelProviderNames.ANTHROPIC_COMPACT -> true;
            default -> false;
        };
        if (!net || config.getMaxRetries() <= 0) return providers.get(provider).create(config);

        DefaultChatModelInvoker invoker = new DefaultChatModelInvoker(
                providers.get(provider).create(config));
        ModelRetryConfig retry = ModelRetryConfig.builder().enabled(true).maxRetries(config.getMaxRetries())
                .initialDelay(Duration.ofMillis(200)).maxDelay(Duration.ofSeconds(2)).build();
        return request -> {
            try {
                return invoker.invoke(() -> ModelChatCommand.builder().chatRequest(request).retryConfig(retry).build());
            } catch (Exception error) {
                if (error instanceof RuntimeException runtime) throw runtime;
                throw new IllegalStateException("Unexpected checked compaction failure", error);
            }
        };
    }
}
