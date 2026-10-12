package com.summit.adapter.langchain4j.codec;

import com.summit.core.conversation.message.TokenUsageEntity;
import dev.langchain4j.data.message.AiMessage;
import dev.langchain4j.model.anthropic.AnthropicTokenUsage;
import dev.langchain4j.model.chat.response.ChatResponse;
import dev.langchain4j.model.chat.response.ChatResponseMetadata;
import dev.langchain4j.model.output.TokenUsage;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class AnthropicMessageCodecAdapterTest {

    @Test
    void includesCacheCreationAndReadInInputAndTotalCounts() {
        assertUsage(AnthropicTokenUsage.builder().inputTokenCount(100).outputTokenCount(30)
                .cacheCreationInputTokens(20).cacheReadInputTokens(60).build(), 210, 180, 30, 60);
    }

    @Test
    void acceptsMissingOptionalCacheCountsAndMissingUsage() {
        assertUsage(AnthropicTokenUsage.builder().inputTokenCount(100).outputTokenCount(30).build(),
                130, 100, 30, 0);
        assertUsage(AnthropicTokenUsage.builder().build(), 0, 0, 0, 0);
        assertUsage(null, 0, 0, 0, 0);
    }

    @Test
    void handlesCacheOnlyInputAndGenericUsage() {
        assertUsage(AnthropicTokenUsage.builder().cacheReadInputTokens(60).build(), 60, 60, 0, 60);
        assertUsage(AnthropicTokenUsage.builder().cacheCreationInputTokens(20).build(), 20, 20, 0, 0);
        assertUsage(new TokenUsage(100, 30, 130), 130, 100, 30, 0);
        assertUsage(new TokenUsage(null, null, null), 0, 0, 0, 0);
    }

    private void assertUsage(TokenUsage usage, int total, int input, int output, int cache) {
        TokenUsageEntity converted = new AnthropicMessageCodecAdapter().toChatResponseEntity(ChatResponse.builder()
                .aiMessage(AiMessage.from("answer"))
                .metadata(ChatResponseMetadata.builder().tokenUsage(usage).build()).build()).getTokenUsage();
        assertEquals(total, converted.getTotalTokens());
        assertEquals(input, converted.getInputTokens().getInputTokens());
        assertEquals(output, converted.getOutputTokens().getOutputTokens());
        assertEquals(cache, converted.getInputTokens().getCacheHitTokens());
        assertEquals(0, converted.getOutputTokens().getReasoningTokens());
    }
}
