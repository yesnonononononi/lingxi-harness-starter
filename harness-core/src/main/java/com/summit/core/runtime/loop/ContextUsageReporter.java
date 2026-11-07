package com.summit.core.runtime.loop;

import com.summit.core.agent.Execution;
import com.summit.core.compact.ContextUsageMetric;
import com.summit.core.compact.Tokenizer;
import com.summit.core.conversation.event.ContextUpdateEvent;
import com.summit.core.conversation.event.RuntimeEventPublisher;
import com.summit.core.conversation.message.Message;
import com.summit.core.conversation.message.TokenUsageEntity;
import com.summit.core.tool.ToolDefinition;
import com.summit.core.tool.ToolRegistry;
import lombok.extern.slf4j.Slf4j;

import java.util.List;
import java.util.function.Supplier;

@Slf4j
public class ContextUsageReporter {
    public final Tokenizer tokenizer;
    private final int maxTokens;
    private final RuntimeEventPublisher publisher;
    /**
     * Resolver of the process-wide tool registry, never the registry itself.
     *
     * <p>The registry is assembled from the tool definitions, and one of them is the
     * context-compaction tool whose compacter needs this reporter. Holding the instance here would
     * therefore close a construction cycle and the application context could not start at all.
     * Keeping a resolver defers the lookup to the moment a usage figure is actually needed — long
     * after every bean exists — and removes any dependence on bean creation order. A context that
     * publishes no process-wide tools answers null here, which is why the resolver is allowed to.</p>
     *
     * <p>The lookup happens per call rather than once being cached: a tool registered while the
     * process runs must be counted by the very next usage report.</p>
     */
    private final Supplier<ToolRegistry> toolRegistry;
    /**
     * Rounds between two notifications, floored at one.
     *
     * <p>Usage is telemetry for a progress display. A caller that passes zero or a negative number
     * means "no preference", not
     * "never report" — a silent reporter is indistinguishable from a broken one, so the floor keeps
     * it publishing every round.</p>
     */
    private final int reportInterval;

    /**
     * Reporter for a context without process-wide tools.
     *
     * @param tokenizer      estimates the tokens of the conversation
     * @param maxTokens      input budget of the model; zero or negative means "unknown"
     * @param publisher      sink of the context-update events
     * @param reportInterval rounds between two notifications, floored at one
     */
    public ContextUsageReporter(Tokenizer tokenizer, int maxTokens, RuntimeEventPublisher publisher,
                                int reportInterval) {
        this(tokenizer, maxTokens, publisher, reportInterval, () -> null);
    }

    /**
     * @param tokenizer      estimates the tokens of the conversation
     * @param maxTokens      input budget of the model; zero or negative means "unknown"
     * @param publisher      sink of the context-update events
     * @param reportInterval rounds between two notifications, floored at one
     * @param toolRegistry   resolver of the process-wide tool registry, invoked on every usage
     *                       calculation; may answer null when the application publishes no tools
     */
    public ContextUsageReporter(Tokenizer tokenizer, int maxTokens, RuntimeEventPublisher publisher,
                                int reportInterval, Supplier<ToolRegistry> toolRegistry) {
        this.tokenizer = tokenizer;
        this.toolRegistry = toolRegistry == null ? () -> null : toolRegistry;
        this.maxTokens = maxTokens;
        this.publisher = publisher;
        this.reportInterval = Math.max(reportInterval, 1);
    }

    public void afterRound(Execution execution) {
        if (execution.getModelAttempts() % reportInterval == 0) publish(execution);
    }

    public ContextUsageMetric report(Execution execution) {
        if (maxTokens <= 0) return null;
        return ContextUsageMetric.of(baseline(execution).getTotalTokens(), maxTokens);
    }

    /** Synchronizes appended messages, including input injected by before-model callbacks. */
    public TokenUsageEntity baseline(Execution execution) {
        Execution.ContextTokenBaseline counted = execution.getContextTokenBaseline();
        List<Message> messages = execution.getMessages() == null ? List.of() : execution.getMessages();
        List<ToolDefinition<?>> tools = registeredTools(execution);
        if (counted == null || counted.messages() != messages || counted.messageCount() > messages.size()
                || (counted.messageCount() == 0 && !messages.isEmpty())
                || !counted.tools().equals(tools)) {
            return rebuildBaseline(execution, messages, tools);
        }
        if (counted.messageCount() < messages.size()) {
            // Recount the boundary message with the suffix: this cancels request framing and
            // preserves the tokenizer's join with the preceding message instead of adding a primer per round.
            int from = Math.max(0, counted.messageCount() - 1);
            int increment = tokenizer.count(messages.subList(from, messages.size()));
            if (counted.messageCount() > 0) {
                increment -= counted.lastMessageTokens();
            }
            int lastMessageTokens = tokenizer.count(messages.getLast());
            counted.usage().setTotalTokens(Math.max(0, counted.usage().getTotalTokens() + increment));
            execution.setContextTokenBaseline(new Execution.ContextTokenBaseline(
                    counted.usage(), messages, messages.size(), lastMessageTokens, tools));
        }
        return counted.usage();
    }

    /** Rebuilds once after compaction, before optional event delivery and its exception isolation. */
    public TokenUsageEntity rebuildBaseline(Execution execution) {
        return rebuildBaseline(execution, execution.getMessages() == null ? List.of() : execution.getMessages(),
                registeredTools(execution));
    }

    private TokenUsageEntity rebuildBaseline(Execution execution, List<Message> messages,
                                            List<ToolDefinition<?>> tools) {
        int tokens = tokenizer.count(messages, tools);
        int lastMessageTokens = messages.isEmpty() ? 0 : tokenizer.count(messages.getLast());
        Execution.ContextTokenBaseline counted = execution.getContextTokenBaseline();
        TokenUsageEntity baseline = counted == null ? TokenUsageEntity.empty() : counted.usage();
        baseline.setTotalTokens(tokens);
        execution.setContextTokenBaseline(new Execution.ContextTokenBaseline(baseline, messages, messages.size(),
                lastMessageTokens, tools));
        return baseline;
    }

    public int countInput(Execution execution, List<Message> messages) {
        return tokenizer.count(messages, registeredTools(execution));
    }


    /** Tools of the executed request, resolved at call time; empty when the context has no registry. */
    private List<ToolDefinition<?>> registeredTools(Execution execution) {
        ToolRegistry registry = toolRegistry.get();

        if (execution == null || registry == null) {
            return List.of();
        }
       return registry.registeredTools(execution);
    }

    public void publish(Execution execution) {
        publish(execution, ContextUpdateEvent.Phase.UPDATE, null, "");
    }

    public void publish(Execution execution, ContextUpdateEvent.Phase phase, String message) {
        publish(execution, phase, null, message);
    }

    /** Compaction events name the band that produced them so consumers can decide per band. */
    public void publish(Execution execution, ContextUpdateEvent.Phase phase,
                        ContextUpdateEvent.Band band, String message) {
        if (phase == ContextUpdateEvent.Phase.SQUEEZE_COMPLETED) rebuildBaseline(execution);
        try {
            if (publisher == null) return;
            publisher.onContextUpdate(new ContextUpdateEvent(
                    execution.getId(),
                    phase,
                    band,
                    report(execution),
                    message, execution.eventMetaData()));
        } catch (Exception e) {
            log.warn("Context usage notification failed: executionId={}", execution.getId(), e);
        }
    }
}
