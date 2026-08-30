package com.summit.core.conversation.message;

import lombok.*;

@ToString
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Getter
public class TokenUsageEntity {
    private int totalTokens;
    private int inputTokens;
    private int outputTokens;

    public static TokenUsageEntity of(int totalTokens, int inputTokens, int outputTokens) {
        return new TokenUsageEntity(totalTokens, inputTokens, outputTokens);
    }
    public static TokenUsageEntity empty(){
        return of(0, 0, 0);
    }

    /** Accumulates the given usage into this instance for session-level aggregation across turns. */
    public void add(TokenUsageEntity other) {
        if (other == null) {
            return;
        }
        this.totalTokens += other.totalTokens;
        this.inputTokens += other.inputTokens;
        this.outputTokens += other.outputTokens;
    }
}
