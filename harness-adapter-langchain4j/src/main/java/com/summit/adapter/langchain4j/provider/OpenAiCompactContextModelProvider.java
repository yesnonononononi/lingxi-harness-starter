package com.summit.adapter.langchain4j.provider;

import com.summit.adapter.langchain4j.model.ChatModelAdapter;
import com.summit.core.model.CompactContextModelProvider;
import com.summit.core.model.ModelConfig;
import dev.langchain4j.model.openai.OpenAiChatModel;

/** Model provider dedicated to context compaction (OpenAI protocol, no thinking), config prefix {@code lingxi.agent.model.conf.compact}. */
public class OpenAiCompactContextModelProvider implements CompactContextModelProvider {
    @Override
    public String name() {
        return "default-compact";
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
