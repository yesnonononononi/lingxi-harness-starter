package com.summit.adapter.langchain4j.codec.message;

import com.summit.core.conversation.message.TokenUsageEntity;
import dev.langchain4j.model.openai.OpenAiTokenUsage;
import dev.langchain4j.model.output.TokenUsage;

/** OpenAI message codec preserving cached input and reasoning output token counts. */
public class OpenAiMessageCodecAdapter extends MessageCodecAdapter {

    @Override
    protected TokenUsageEntity toTokenUsageEntity(TokenUsage tokenUsage) {
        if (!(tokenUsage instanceof OpenAiTokenUsage usage)) {
            return super.toTokenUsageEntity(tokenUsage);
        }
        return new TokenUsageEntity(orZero(usage.totalTokenCount()),
                new TokenUsageEntity.InputTokens(orZero(usage.inputTokenCount()),
                        usage.inputTokensDetails() == null ? 0 : orZero(usage.inputTokensDetails().cachedTokens())),
                new TokenUsageEntity.OutputTokens(orZero(usage.outputTokenCount()),
                        usage.outputTokensDetails() == null ? 0 : orZero(usage.outputTokensDetails().reasoningTokens())));
    }
}
