package com.summit.adapter.langchain4j.provider;

import com.summit.adapter.langchain4j.model.StreamingChatModelAdapter;
import com.summit.harnesscore.model.ModelConfig;
import com.summit.harnesscore.model.StreamingChatModelProvider;
import dev.langchain4j.model.openai.OpenAiStreamingChatModel;

/** OpenAI-protocol streaming model provider, provider name {@code default-streaming}. */
public class OpenAiStreamingModelProvider implements StreamingChatModelProvider {
    @Override
    public String name() {
        return "default-streaming";
    }

    @Override
    public StreamingChatModelAdapter create(ModelConfig config) {
        return new StreamingChatModelAdapter(OpenAiStreamingChatModel.builder()
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
