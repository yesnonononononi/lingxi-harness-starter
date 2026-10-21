package com.summit.adapter.langchain4j.model.provider;

import com.summit.adapter.langchain4j.model.ChatModelAdapter;
import com.summit.core.conf.ModelConfig;
import com.summit.core.model.DefaultModelProviderNames;
import com.summit.core.model.chat.ChatModelProvider;
import dev.langchain4j.model.anthropic.AnthropicChatModel;

/** Native Anthropic protocol provider. */
public class AnthropicChatModelProvider implements ChatModelProvider {
    @Override
    public String name() { return DefaultModelProviderNames.ANTHROPIC; }

    @Override
    public ChatModelAdapter create(ModelConfig config) {
        AnthropicModelSettings settings = AnthropicModelSettings.resolve(config);
        return new ChatModelAdapter(
                AnthropicChatModel.builder()
                .baseUrl(config.getBaseUrl())
                .apiKey(config.getApiKey())
                .modelName(config.getModelName())
                .timeout(config.getTimeout())
                .maxRetries(config.getMaxRetries())
                .maxTokens(settings.maxTokens())
                .sendThinking(config.isSendThinking())
                .returnThinking(config.isReturnThinking())
                .thinkingType(settings.thinkingType())
                .thinkingBudgetTokens(settings.budget())
                .customParameters(settings.customParameters())
                .cacheSystemMessages(settings.cachePrefix())
                .cacheTools(settings.cachePrefix())
                .cacheAutomatically(settings.cacheAutomatically())
                .cacheTtl(settings.cacheTtl())
                .build()
        );
    }
}
