package com.summit.runtime.conversation;

import com.summit.core.compact.ContextUsageMetric;
import com.summit.core.conversation.context.RuntimeContext;
import com.summit.core.conversation.event.ContextUpdateEvent;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

import java.io.Serializable;

/**
 * Reports how much of the context window the running session currently occupies, so the front-end
 * gauge follows a run instead of only reacting to compactions.
 *
 * <p>The reported value is the <b>real</b> context size — the token count of the whole stored
 * conversation against the configured cap. It is deliberately not derived from the per-round model
 * usage: every round's {@code totalTokens} already contains the entire prompt, so accumulating them
 * describes nothing (and the running total would keep growing even after a compaction shrank the
 * context).</p>
 *
 * <p>Counting the whole conversation is not free, so publishing is throttled to one event every
 * {@value #PUBLISH_INTERVAL_ROUNDS} completed rounds; {@link #publish} forces the final value when
 * the loop ends, so a short run still updates the gauge.</p>
 *
 * <p>Reporting is best-effort telemetry: a failure here is logged instead of propagating, because it
 * must never turn a healthy execution into a failed one.</p>
 */
@RequiredArgsConstructor
@Slf4j
public class ContextUsageReporter {

    /** Rounds between two usage events. */
    static final int PUBLISH_INTERVAL_ROUNDS = 5;

    private final RuntimeContext context;

    private int roundsSincePublish;

    /** Throttled publish, called once per completed round. */
    public void afterRound(Serializable sessionId, String executionId) {
        if (++roundsSincePublish < PUBLISH_INTERVAL_ROUNDS) {
            return;
        }
        publish(sessionId, executionId);
    }

    /** Unconditional publish; used to flush the last, partial window when the loop ends. */
    public void publish(Serializable sessionId, String executionId) {
        roundsSincePublish = 0;
        try {
            int maxTokens = context.getMaxTokens();
            int tokenCount = context.getTokenizer().count(context.getConversationManager().messages(sessionId));
            context.getRuntimeEventPublisher().onContextUpdate(new ContextUpdateEvent(
                    sessionId, executionId, ContextUpdateEvent.Phase.UPDATE,
                    ContextUsageMetric.of(tokenCount, maxTokens), ""));
        } catch (Exception e) {
            log.warn("【context-usage】failed to publish context usage: sessionId={}, error={}",
                    sessionId, e.getMessage());
        }
    }
}
