package com.summit.adapter.langchain4j.codec.message;

import com.summit.core.conversation.message.TokenUsageEntity;
import dev.langchain4j.model.anthropic.AnthropicTokenUsage;
import dev.langchain4j.model.output.TokenUsage;

/** Anthropic message codec normalizing uncached, cache creation and cache read input tokens. */
public class AnthropicMessageCodecAdapter extends MessageCodecAdapter {

    @Override
    protected TokenUsageEntity toTokenUsageEntity(TokenUsage tokenUsage) {
        if (!(tokenUsage instanceof AnthropicTokenUsage usage)) {
            return super.toTokenUsageEntity(tokenUsage);
        }
        // Anthropic reports these input categories separately; core input tokens include all three.
        int cacheHitTokens = orZero(usage.cacheReadInputTokens());

        int inputTokens = orZero(usage.inputTokenCount())
                + orZero(usage.cacheCreationInputTokens()) + cacheHitTokens;

        int outputTokens = orZero(usage.outputTokenCount());
        // This SDK exposes no separate reasoning token count for Anthropic.
        return new TokenUsageEntity(inputTokens + outputTokens,
                new TokenUsageEntity.InputTokens(inputTokens, cacheHitTokens),
                TokenUsageEntity.OutputTokens.of(outputTokens));
    }
}
