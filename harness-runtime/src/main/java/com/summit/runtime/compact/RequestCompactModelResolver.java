package com.summit.runtime.compact;

import com.summit.core.agent.AgentRequest;
import com.summit.core.conf.ModelConfig;
import com.summit.core.conf.SensitiveDataProvider;
import com.summit.core.conf.SensitiveDataSpec;
import com.summit.core.model.DefaultModelProviderNames;
import com.summit.core.model.ModelProviderRegistry;
import com.summit.core.model.ModelProviderRouting;
import com.summit.core.model.chat.ChatModel;

import java.util.Objects;

/** Resolves exact request revisions without retaining credentials in executions or shared mutable state. */
public final class RequestCompactModelResolver {
    private final ModelConfig applicationConfig;
    private final SensitiveDataProvider<ModelConfig> configurations;
    private final ModelProviderRegistry<ChatModel> providers;
    private final ChatModel applicationCompactModel;

    public RequestCompactModelResolver(ModelConfig applicationConfig, SensitiveDataProvider<ModelConfig> configurations,
                                       ModelProviderRegistry<ChatModel> providers, ChatModel applicationCompactModel) {
        this.applicationConfig = applicationConfig;
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
                case DefaultModelProviderNames.DEFAULT_STREAMING -> DefaultModelProviderNames.DEFAULT;
                case DefaultModelProviderNames.ANTHROPIC_STREAMING -> DefaultModelProviderNames.ANTHROPIC;
                default -> provider;
            };
            selected = config.toBuilder().provider(provider).build();

        } else if (config.getCompactModel() != null) {
            ModelConfig.CompactModelConfig compact = config.getCompactModel();

            if (compact.baseUrl() == null || compact.baseUrl().isBlank()
                    || compact.apiKey() == null || compact.apiKey().isBlank()
                    || compact.modelName() == null || compact.modelName().isBlank()) {
                throw new IllegalArgumentException("Compact model baseUrl, apiKey and modelName must all be non-blank");
            }

            selected = ModelConfig.builder()
                    .baseUrl(compact.baseUrl())
                    .apiKey(compact.apiKey())
                    .modelName(compact.modelName())
                    .provider(ModelProviderRouting.compactProvider(null, compact.modelName()))
                    .timeout(config.getTimeout())
                    .maxTokens(config.getMaxTokens())
                    .maxRetries(config.getMaxRetries())
                    .build();

        } else {
            return applicationCompactModel;
        }
        return providers.get(selected.getProvider()).create(selected);
    }
}
