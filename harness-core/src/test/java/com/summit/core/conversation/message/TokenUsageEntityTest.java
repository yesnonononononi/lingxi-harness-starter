package com.summit.core.conversation.message;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.summit.core.conversation.event.TokenInfo;
import com.summit.core.conversation.token.ImageTokenUsage;
import com.summit.core.conversation.token.TextTokenUsage;
import com.summit.core.json.ExecutionJson;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class TokenUsageEntityTest {
    @Test
    void mixedUsageKeepsAllDetailsAndRestoresThroughTheExistingBaseType() throws Exception {
        var mapper = ExecutionJson.newObjectMapper();
        TokenUsageEntity image = ImageTokenUsage.builder().totalTokens(95)
                .inputTokens(new TokenUsageEntity.InputTokens(15, 0, 10, 5))
                .outputTokens(new TokenUsageEntity.OutputTokens(80, 0, 78, 2)).build();
        TokenUsageEntity text = TextTokenUsage.builder().totalTokens(130)
                .inputTokens(new TokenUsageEntity.InputTokens(100, 60))
                .outputTokens(new TokenUsageEntity.OutputTokens(30, 12)).build();
        TokenUsageEntity total = TokenUsageEntity.empty();
        total.add(image);
        total.add(text);
        total.add(null);
        assertEquals(new TokenInfo(115, 110, 225, 12, 60), TokenInfo.from(total));
        assertEquals(new TokenUsageEntity.InputTokens(115, 60, 10, 5), total.getInputTokens());
        assertEquals(new TokenUsageEntity.OutputTokens(110, 12, 78, 2), total.getOutputTokens());
        assertEquals(15, image.getInputTokens().getInputTokens());
        assertEquals(80, image.getOutputTokens().getOutputTokens());
        for (TokenUsageEntity usage : new TokenUsageEntity[]{image, text, total}) {
            String json = mapper.writeValueAsString(usage);
            TokenUsageEntity restored = mapper.readValue(json, TokenUsageEntity.class);
            assertEquals(usage.getInputTokens(), restored.getInputTokens());
            assertEquals(usage.getOutputTokens(), restored.getOutputTokens());
            assertEquals(mapper.readTree(json), mapper.valueToTree(mapper.readValue(json, usage.getClass())));
        }
        assertFalse(mapper.valueToTree(text).path("inputTokens").has("textTokens"));
    }

    @Test
    void imageCountsAccumulateWithoutChangingAggregateOrSourceCounts() {
        TokenUsageEntity usage = TokenUsageEntity.empty();
        TokenUsageEntity source = new TokenUsageEntity(100, new TokenUsageEntity.InputTokens(20, 3, 10),
                new TokenUsageEntity.OutputTokens(80, 0, 75));
        usage.add(source);
        usage.add(source);
        assertEquals(200, usage.getTotalTokens());
        assertEquals(new TokenUsageEntity.InputTokens(40, 6, 20), usage.getInputTokens());
        assertEquals(new TokenUsageEntity.OutputTokens(160, 0, 150), usage.getOutputTokens());
        assertEquals(10, source.getInputTokens().getImageTokens());
        assertEquals(75, source.getOutputTokens().getImageTokens());
    }

    @Test
    void accumulatesAllCountsWithoutMutatingTheSource() {
        TokenUsageEntity accumulated = TokenUsageEntity.empty();
        TokenUsageEntity source = new TokenUsageEntity(130, new TokenUsageEntity.InputTokens(100, 60),
                new TokenUsageEntity.OutputTokens(30, 12));
        accumulated.add(source);
        accumulated.add(source);
        accumulated.add(null);
        assertEquals(new TokenInfo(200, 60, 260, 24, 120), TokenInfo.from(accumulated));
        assertEquals(new TokenInfo(100, 30, 130, 12, 60), TokenInfo.from(source));
        assertNotSame(source.getInputTokens(), accumulated.getInputTokens());
        assertNotSame(source.getOutputTokens(), accumulated.getOutputTokens());
    }

    @Test
    void defaultsAreUsableForAccumulation() {
        for (TokenUsageEntity usage : new TokenUsageEntity[]{TokenUsageEntity.empty(),
                new TokenUsageEntity(), TokenUsageEntity.builder().build()}) {
            usage.add(TokenUsageEntity.of(3, 2, 1));
            assertEquals(new TokenInfo(2, 1, 3, 0, 0), TokenInfo.from(usage));
        }
    }

    @Test
    void projectsMissingUsageAndDetailsWithoutInventingCounts() {
        assertNull(TokenInfo.from(null));
        TokenUsageEntity usage = new TokenUsageEntity(0, null, null);
        assertEquals(new TokenInfo(null, null, 0, null, null), TokenInfo.from(usage));
        usage.add(new TokenUsageEntity(5, null, new TokenUsageEntity.OutputTokens(5, 2)));
        assertEquals(new TokenInfo(0, 5, 5, 2, 0), TokenInfo.from(usage));
    }

    @Test
    void retainsReportedCacheMissesAcrossAccumulationAndJsonRestore() throws Exception {
        ObjectMapper mapper = ExecutionJson.newObjectMapper();
        TokenUsageEntity legacy = mapper.readValue("""
                {"totalTokens":12,"inputTokens":{"inputTokens":10,"cacheHitTokens":3},
                 "outputTokens":{"outputTokens":2}}
                """, TokenUsageEntity.class);
        assertNull(legacy.getInputTokens().getCacheMissTokens());

        TokenUsageEntity reported = new TokenUsageEntity(26,
                new TokenUsageEntity.InputTokens(20, 7, 0, null, 13), TokenUsageEntity.OutputTokens.of(6));
        legacy.add(reported);
        legacy.add(reported);
        TokenUsageEntity restored = mapper.readValue(mapper.writeValueAsString(legacy), TokenUsageEntity.class);

        assertEquals(64, restored.getTotalTokens());
        assertEquals(50, restored.getInputTokens().getInputTokens());
        assertEquals(17, restored.getInputTokens().getCacheHitTokens());
        assertEquals(26, restored.getInputTokens().getCacheMissTokens());
        assertEquals(13, reported.getInputTokens().getCacheMissTokens());
        assertFalse(mapper.valueToTree(TokenUsageEntity.empty()).path("inputTokens").has("cacheMissTokens"));
    }
}
