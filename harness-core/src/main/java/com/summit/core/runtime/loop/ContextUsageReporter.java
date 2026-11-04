package com.summit.core.runtime.loop;

import com.summit.core.agent.Execution;
import com.summit.core.compact.ContextUsageMetric;
import com.summit.core.compact.Tokenizer;
import com.summit.core.conversation.event.ContextUpdateEvent;
import com.summit.core.conversation.event.RuntimeEventPublisher;
import com.summit.core.conversation.message.Message;
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
     * <p>Usage is telemetry for a progress display, and producing it counts the tokens of the whole
     * conversation. A caller that passes zero or a negative number means "no preference", not
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
        List<ToolDefinition<?>> tools = registeredTools(execution);
        if (tools.isEmpty()) return tokenizer.usage(execution.getMessages(), maxTokens);
        if (maxTokens <= 0) return null;
        return ContextUsageMetric.of(tokenizer.count(execution.getMessages(), tools), maxTokens);
    }

    public int countInput(Execution execution, List<Message> messages) {
        return tokenizer.count(messages, registeredTools(execution));
    }

    public double inputRatio(Execution execution, List<Message> messages, int tokenBudget) {
        List<ToolDefinition<?>> tools = registeredTools(execution);
        return tools.isEmpty() ? tokenizer.calcCurrentTokenRatio(messages, tokenBudget)
                : tokenizer.count(messages, tools) / (double) tokenBudget;
    }

    /** Tools of the executed request, resolved at call time; empty when the context has no registry. */
    private List<ToolDefinition<?>> registeredTools(Execution execution) {
        if (execution == null || execution.getAgentRequest() == null) {
            return List.of();
        }
        List<String> allowed = execution.getAgentRequest().getToolList();
        ToolRegistry registry = toolRegistry.get();
        if (registry == null || allowed == null || allowed.isEmpty()) {
            return List.of();
        }
        return List.copyOf(registry.getToolDefinitionList().stream()
                .filter(tool -> tool.allowedFor(allowed)).toList());
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
        try {
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
