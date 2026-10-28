package com.summit.core.model;

import com.summit.core.conversation.message.TokenUsageEntity;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import static org.junit.jupiter.api.Assertions.*;

class ModelInvocationUsageTest {
    @Test
    void absentUsageRemainsUnknown() {
        ModelInvocationUsage usage = new ModelInvocationUsage();
        usage.recordUsage("1", null);
        assertNull(usage.finish());
    }

    @Test
    void reportedZeroDiffersFromAbsentUsage() {
        ModelInvocationUsage usage = new ModelInvocationUsage();
        usage.recordUsage("1", TokenUsageEntity.empty());
        TokenUsageEntity total = usage.finish();
        assertNotNull(total);
        assertEquals(0, total.getTotalTokens());
    }

    @Test
    void eachResponseContributesOnceWithoutMutatingItsUsage() {
        ModelInvocationUsage usage = new ModelInvocationUsage();
        TokenUsageEntity first = new TokenUsageEntity(10, new TokenUsageEntity.InputTokens(7, 3, 2),
                new TokenUsageEntity.OutputTokens(3, 1, 1));
        usage.recordUsage("1", first);
        usage.recordUsage("1", TokenUsageEntity.of(999, 999, 0));
        usage.recordUsage("2", new TokenUsageEntity(20, new TokenUsageEntity.InputTokens(14, 6, 4),
                new TokenUsageEntity.OutputTokens(6, 2, 2)));

        TokenUsageEntity total = usage.finish();
        assertEquals(30, total.getTotalTokens());
        assertEquals(new TokenUsageEntity.InputTokens(21, 9, 6), total.getInputTokens());
        assertEquals(new TokenUsageEntity.OutputTokens(9, 3, 3), total.getOutputTokens());
        assertEquals(10, first.getTotalTokens());
        assertEquals(7, first.getInputTokens().getInputTokens());
    }

    @Test
    void capturedUsageIsDetachedFromLaterResponseMutations() {
        ModelInvocationUsage usage = new ModelInvocationUsage();
        TokenUsageEntity response = TokenUsageEntity.of(10, 7, 3);
        usage.recordUsage("1", response);
        response.add(TokenUsageEntity.of(20, 14, 6));
        TokenUsageEntity total = usage.finish();
        assertEquals(10, total.getTotalTokens());
        assertNotSame(response.getInputTokens(), total.getInputTokens());
        assertNotSame(response.getOutputTokens(), total.getOutputTokens());
    }

    @Test
    void settlementIsSingleUseAndRejectsLateWrites() {
        ModelInvocationUsage usage = new ModelInvocationUsage();
        usage.recordUsage("1", TokenUsageEntity.of(10, 7, 3));
        TokenUsageEntity settled = usage.finish();
        usage.recordUsage("2", TokenUsageEntity.of(20, 14, 6));
        assertEquals(10, settled.getTotalTokens());
        assertNull(usage.finish());
    }

    @Test
    void finishingAnEmptyScopeAlsoRejectsLateWrites() {
        ModelInvocationUsage usage = new ModelInvocationUsage();
        assertNull(usage.finish());
        usage.recordUsage("1", TokenUsageEntity.of(10, 7, 3));
        assertNull(usage.finish());
    }

    @Test
    void separateInvocationsDoNotShareAttemptIdentity() {
        ModelInvocationUsage first = new ModelInvocationUsage();
        ModelInvocationUsage second = new ModelInvocationUsage();
        first.recordUsage("1", TokenUsageEntity.of(10, 7, 3));
        second.recordUsage("1", TokenUsageEntity.of(20, 14, 6));
        assertEquals(10, first.finish().getTotalTokens());
        assertEquals(20, second.finish().getTotalTokens());
    }

    @Test
    void concurrentCallbacksCannotDuplicateAnAttemptOrLoseDistinctAttempts() throws Exception {
        ModelInvocationUsage usage = new ModelInvocationUsage();
        try (ExecutorService executor = Executors.newFixedThreadPool(4)) {
            List<Future<?>> callbacks = new ArrayList<>();
            for (int index = 0; index < 80; index++) {
                String responseId = Integer.toString(index % 20);
                callbacks.add(executor.submit(() -> usage.recordUsage(responseId, TokenUsageEntity.of(3, 2, 1))));
            }
            for (Future<?> callback : callbacks) callback.get();
        }
        assertEquals(60, usage.finish().getTotalTokens());
    }
}
