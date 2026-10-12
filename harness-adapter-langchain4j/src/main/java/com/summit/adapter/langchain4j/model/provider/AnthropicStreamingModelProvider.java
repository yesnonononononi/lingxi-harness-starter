package com.summit.adapter.langchain4j.model.provider;

import com.summit.adapter.langchain4j.model.StreamingChatModelAdapter;
import com.summit.core.conf.ModelConfig;
import com.summit.core.model.DefaultModelProviderNames;
import com.summit.core.model.streaming.StreamingChatModelProvider;
import dev.langchain4j.model.anthropic.AnthropicStreamingChatModel;

/** Native Anthropic protocol provider. */
public class AnthropicStreamingModelProvider implements StreamingChatModelProvider {
    @Override
    public String name() { return DefaultModelProviderNames.ANTHROPIC_STREAMING; }

    @Override
    public StreamingChatModelAdapter create(ModelConfig config) {
        AnthropicModelSettings settings = AnthropicModelSettings.resolve(config);
        return new StreamingChatModelAdapter(AnthropicStreamingChatModel.builder()
                .baseUrl(config.getBaseUrl())
                .apiKey(config.getApiKey())
                .modelName(config.getModelName())
                .timeout(config.getTimeout())
                .maxTokens(settings.maxTokens())
                .sendThinking(config.isSendThinking())
                .returnThinking(config.isReturnThinking())
                .thinkingType(settings.thinkingType())
                .thinkingBudgetTokens(settings.budget())
                .customParameters(settings.customParameters())
                .build());
    }
}
