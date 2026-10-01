package com.summit.core.conversation.event;

import com.summit.core.conversation.message.TokenUsageEntity;
import lombok.Builder;

/**
 * Token usage carried by every terminal execution event (completed / failed / cancelled).
 *
 * <p>One shape for all three terminal events, so a consumer never needs a per-event branch.
 * A {@code null} count means "not collected" and is deliberately distinct from a real zero.</p>
 */
@Builder
public record TokenInfo(Integer inputTokenCount, Integer outputTokenCount, Integer totalTokenCount) {

    /** Projects an execution's accumulated usage; missing usage stays {@code null} instead of zero. */
    public static TokenInfo from(TokenUsageEntity usage) {
        if (usage == null) {
            return null;
        }
        return TokenInfo.builder()
                .inputTokenCount(usage.getInputTokens())
                .outputTokenCount(usage.getOutputTokens())
                .totalTokenCount(usage.getTotalTokens())
                .build();
    }
}
