package com.summit.core.runtime.loop;

import com.summit.core.agent.Execution;
import com.summit.core.compact.ContextUsageMetric;
import com.summit.core.compact.Tokenizer;
import com.summit.core.conversation.event.ContextUpdateEvent;
import com.summit.core.conversation.event.RuntimeEventPublisher;
import lombok.extern.slf4j.Slf4j;

@Slf4j
public class ContextUsageReporter {
    public final Tokenizer tokenizer;
    private final int maxTokens;
    private final RuntimeEventPublisher publisher;
    /**
     * Rounds between two notifications, floored at one.
     *
     * <p>Usage is telemetry for a progress display, and producing it counts the tokens of the whole
     * conversation. A caller that passes zero or a negative number means "no preference", not
     * "never report" — a silent reporter is indistinguishable from a broken one, so the floor keeps
     * it publishing every round.</p>
     */
    private final int reportInterval;

    public ContextUsageReporter(Tokenizer tokenizer, int maxTokens, RuntimeEventPublisher publisher,
                                int reportInterval) {
        this.tokenizer = tokenizer;
        this.maxTokens = maxTokens;
        this.publisher = publisher;
        this.reportInterval = Math.max(reportInterval, 1);
    }

    public void afterRound(Execution execution) {
        if (execution.getModelAttempts() % reportInterval == 0) publish(execution);
    }

    public ContextUsageMetric report(Execution execution){
        return this.tokenizer.usage(execution.getMessages(), maxTokens);
    }

    public void publish(Execution execution) {
        publish(execution, ContextUpdateEvent.Phase.UPDATE, "");
    }

    public void publish(Execution execution, ContextUpdateEvent.Phase phase, String message) {
        try {
            publisher.onContextUpdate(new ContextUpdateEvent(
                    execution.getId(),
                    phase,
                    tokenizer.usage(execution.getMessages(), maxTokens),
                    message, execution.eventMetaData()));
        } catch (Exception e) {
            log.warn("Context usage notification failed: executionId={}", execution.getId(), e);
        }
    }
}
