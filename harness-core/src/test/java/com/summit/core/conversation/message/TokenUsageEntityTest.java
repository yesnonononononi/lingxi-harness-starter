package com.summit.core.conversation.message;

import com.summit.core.conversation.event.TokenInfo;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class TokenUsageEntityTest {
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
}
