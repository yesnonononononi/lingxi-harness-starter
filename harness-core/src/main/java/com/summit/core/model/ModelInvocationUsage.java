package com.summit.core.model;

import com.summit.core.conversation.message.TokenUsageEntity;

import java.util.HashSet;
import java.util.Objects;
import java.util.Set;

/** Owns collected usage for one invocation, including its retry attempts. */
public final class ModelInvocationUsage {
    private final Set<String> recordedResponses = new HashSet<>();
    private TokenUsageEntity total;
    private boolean finished;

    /** Copies each attempt's usage once; unknown usage remains uncollected. */
    public synchronized void recordUsage(String responseId, TokenUsageEntity usage) {
        if (finished || usage == null) return;
        if (!recordedResponses.add(Objects.requireNonNull(responseId, "responseId"))) return;
        if (total == null) total = TokenUsageEntity.empty();
        total.add(usage);
    }

    /** Transfers the collected total once and rejects further callback writes. */
    public synchronized TokenUsageEntity finish() {
        if (finished) return null;
        finished = true;
        TokenUsageEntity collected = total;
        total = null;
        recordedResponses.clear();
        return collected;
    }
}
