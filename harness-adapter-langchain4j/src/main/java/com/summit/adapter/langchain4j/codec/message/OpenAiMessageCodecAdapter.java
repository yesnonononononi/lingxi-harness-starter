package com.summit.adapter.langchain4j.codec.message;

import com.summit.core.conversation.message.TokenUsageEntity;
import com.summit.core.conversation.token.ImageTokenUsage;
import com.summit.core.conversation.token.TextTokenUsage;
import dev.langchain4j.model.openai.OpenAiImageTokenUsage;
import dev.langchain4j.model.openai.OpenAiTokenUsage;
import dev.langchain4j.model.output.TokenUsage;

/** OpenAI message codec preserving cache, reasoning and image/text token breakdowns. */
public class OpenAiMessageCodecAdapter extends MessageCodecAdapter {

    @Override
    public TokenUsageEntity toTokenUsageEntity(TokenUsage tokenUsage) {
        if (tokenUsage instanceof OpenAiImageTokenUsage usage) {
            var input = usage.inputTokensDetails();
            var output = usage.outputTokensDetails();
            // Details are subsets of aggregate counts, never additional tokens.
            return ImageTokenUsage.builder()
                    .totalTokens(orZero(usage.totalTokenCount()))
                    .inputTokens(new TokenUsageEntity.InputTokens(orZero(usage.inputTokenCount()), 0,
                            input == null ? 0 : orZero(input.imageTokens()),
                            input == null ? null : input.textTokens()))
                    .outputTokens(new TokenUsageEntity.OutputTokens(orZero(usage.outputTokenCount()), 0,
                            output == null ? 0 : orZero(output.imageTokens()),
                            output == null ? null : output.textTokens()))
                    .build();
        }

        if (!(tokenUsage instanceof OpenAiTokenUsage usage)) {
            return super.toTokenUsageEntity(tokenUsage);
        }
        return TextTokenUsage.builder()
                .totalTokens(orZero(usage.totalTokenCount()))
                .inputTokens(new TokenUsageEntity.InputTokens(orZero(usage.inputTokenCount()),
                        usage.inputTokensDetails() == null ? 0 : orZero(usage.inputTokensDetails().cachedTokens())))
                .outputTokens(new TokenUsageEntity.OutputTokens(orZero(usage.outputTokenCount()),
                        usage.outputTokensDetails() == null ? 0 : orZero(usage.outputTokensDetails().reasoningTokens())))
                .build();
    }
}
