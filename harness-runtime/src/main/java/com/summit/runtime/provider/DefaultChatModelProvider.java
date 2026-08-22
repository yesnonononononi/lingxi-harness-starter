package com.summit.runtime.provider;

import com.summit.harnesscore.model.ChatModelProvider;
import com.summit.harnesscore.model.ModelConfig;
import dev.langchain4j.model.chat.ChatModel;
import dev.langchain4j.model.openai.OpenAiChatModel;

public class DefaultChatModelProvider implements ChatModelProvider {
    @Override
    public String name() {
        return "default";
    }

    @Override
    public ChatModel create(ModelConfig config) {
        return OpenAiChatModel.builder()
                .baseUrl(config.getBaseUrl())
                .apiKey(config.getApiKey())
                .modelName(config.getModelName())
                .build();
    }
}
