package com.summit.runtime.provider;

import com.summit.harnesscore.model.ModelConfig;

import com.summit.harnesscore.model.StreamingChatModelProvider;
import dev.langchain4j.model.chat.StreamingChatModel;
import dev.langchain4j.model.openai.OpenAiStreamingChatModel;

public class DefaultStreamingModelProvider implements StreamingChatModelProvider {
    @Override
    public String name() {
        return "default-streaming";
    }

    @Override
    public StreamingChatModel create(ModelConfig config) {
        return OpenAiStreamingChatModel.builder()
                .baseUrl(config.getBaseUrl())
                .apiKey(config.getApiKey())
                .sendThinking(config.isSendThinking())
                .returnThinking(config.isReturnThinking())
                .maxTokens(config.getMaxTokens())
                .reasoningEffort(config.getReasoningEffort())
                .modelName(config.getModelName())
                .timeout(config.getTimeout())
                .build();
    }
}
