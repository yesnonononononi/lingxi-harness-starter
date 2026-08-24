package com.summit.runtime.provider;

import com.summit.harnesscore.model.CompactContextModelProvider;
import com.summit.harnesscore.model.ModelConfig;
import dev.langchain4j.model.chat.ChatModel;
import dev.langchain4j.model.openai.OpenAiChatModel;

/** 上下文压缩专用模型默认实现，配置前缀 {@code lingxi.agent.model.conf.compact}。 */
public class DefaultCompactContextModelProvider implements CompactContextModelProvider {
    @Override
    public String name() {
        return "default-compact";
    }

    @Override
    public ChatModel create(ModelConfig config) {
        return OpenAiChatModel.builder()
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
