package com.summit.adapter.langchain4j.provider;

import com.summit.adapter.langchain4j.model.ChatModelAdapter;
import com.summit.core.model.ChatModelProvider;
import com.summit.core.model.ModelConfig;
import dev.langchain4j.model.openai.OpenAiChatModel;

/** OpenAI-protocol reasoning model provider, provider name {@code default}. */
public class OpenAiChatModelProvider implements ChatModelProvider {
    @Override
    public String name() {
        return "default";
    }

    @Override
    public ChatModelAdapter create(ModelConfig config) {
        return new ChatModelAdapter(OpenAiChatModel.builder()
                .baseUrl(config.getBaseUrl())
                .apiKey(config.getApiKey())
                .sendThinking(config.isSendThinking())
                .returnThinking(config.isReturnThinking())
                .maxTokens(config.getMaxTokens())
                .reasoningEffort(config.getReasoningEffort())
                .modelName(config.getModelName())
                .timeout(config.getTimeout())
                .build());
    }
}
