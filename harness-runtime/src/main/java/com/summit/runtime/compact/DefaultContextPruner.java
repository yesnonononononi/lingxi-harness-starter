package com.summit.runtime.compact;

import com.summit.core.agent.Execution;
import com.summit.core.compact.ContextAttachmentProvider;
import com.summit.core.compact.ContextCompactRequest;
import com.summit.core.compact.ContextCompacter;
import com.summit.core.compact.Tokenizer;
import com.summit.core.conversation.api.ToolCallRequest;
import com.summit.core.conversation.event.ContextUpdateEvent;
import com.summit.core.conversation.message.AiMessageEntity;
import com.summit.core.conversation.message.Message;
import com.summit.core.conversation.message.SystemMessageEntity;
import com.summit.core.conversation.message.ToolMessageEntity;
import com.summit.core.exception.TokenBudgetExceededException;
import com.summit.core.runtime.loop.ContextUsageReporter;
import com.summit.runtime.agent.AgentConfig;
import lombok.extern.slf4j.Slf4j;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * Retains assistant findings and the latest working group when summarization is unavailable.
 * Historical results become skeletons first; only their call structures are removed on overflow.
 */
@Slf4j
public class DefaultContextPruner implements ContextCompacter {

    private record History(Map<String, Integer> owners, int latestAssistant, boolean pending) {
    }

    private final AgentConfig agentConfig;
    private final ContextUsageReporter usage;
    private final Tokenizer tokenizer;
    private final ContextAttachmentProvider attachments;

    public DefaultContextPruner(AgentConfig agentConfig, ContextUsageReporter usage) {
        this(agentConfig, usage, ContextAttachmentProvider.NONE);
    }

    public DefaultContextPruner(AgentConfig agentConfig, ContextUsageReporter usage,
                                ContextAttachmentProvider attachments) {
        this.agentConfig = Objects.requireNonNull(agentConfig, "agentConfig");
        this.usage = Objects.requireNonNull(usage, "usage");
        this.tokenizer = Objects.requireNonNull(usage.tokenizer, "usage.tokenizer");
        this.attachments = Objects.requireNonNull(attachments, "attachments");
    }

    @Override
    public boolean compact(ContextCompactRequest request) {
        Execution execution = Objects.requireNonNull(request, "request").execution();

        usage.publish(execution, ContextUpdateEvent.Phase.SQUEEZE_STARTED,
                ContextUpdateEvent.Band.PRUNE, "Context prune started");

        String outcome = "Context prune failed";
        try {
            boolean changed = prune(execution);
            outcome = changed ? "Context prune completed" : "Context prune finished without changes";
            return changed;
        } finally {
            // Failures must close the event pair too, without hiding a terminal budget error.
            usage.publish(execution, ContextUpdateEvent.Phase.SQUEEZE_COMPLETED,
                    ContextUpdateEvent.Band.PRUNE, outcome);
        }
    }

    private boolean prune(Execution execution) {
        List<Message> source = execution.getMessages() == null ? List.of() : execution.getMessages();
        if (source.isEmpty()) return false;

        History history = indexHistory(source);

        // A pending tool continuation must keep its entire signed prefix, not just its last group.
        if (history.pending()) {
            checkBudget(source);
            return false;
        }

        List<Message> input = new ArrayList<>(source);
        attachments.attachment(execution.getId())
                .filter(value -> !value.isBlank())
                .filter(value -> source.stream().noneMatch(message ->
                        message instanceof SystemMessageEntity && message.text().contains(value)))
                .ifPresent(value -> input.add(SystemMessageEntity.builder().text(value).build()));

        boolean dropHistory = execution.getCompactionState().pruneGeneration() >= 2;
        List<Message> product = project(input, history, dropHistory);

        if (!dropHistory && tokenizer.count(product) > agentConfig.maxTokens()) {
            dropHistory = true;
            product = project(input, history, true);
        }

        checkBudget(product);

        if (indexHistory(product).pending()) {
            throw new IllegalStateException("Context pruning left an unanswered tool call");
        }

        // Build and validate both candidates before changing either messages or the persisted stage.
        boolean changed = !source.equals(product);
        execution.setMessages(product);
        execution.setCompactionState(execution.getCompactionState().withPruneGeneration(dropHistory ? 2 : 1));

        log.info("Context prune completed: executionId={}, stage={}, changed={}",
                execution.getId(), dropHistory ? 2 : 1, changed);

        return changed;
    }

    private History indexHistory(List<Message> messages) {
        Map<String, Integer> owners = new HashMap<>();
        int latestAssistant = -1;

        for (int index = 0; index < messages.size(); index++) {
            if (!(messages.get(index) instanceof AiMessageEntity ai)) continue;

            latestAssistant = index;
            if (ai.getToolCalls() == null) continue;

            for (ToolCallRequest call : ai.getToolCalls()) {
                if (call == null || call.id() == null || call.id().isBlank()) {
                    throw new IllegalStateException("Context contains a tool call without an id");
                }

                if (owners.putIfAbsent(call.id(), index) != null) {
                    throw new IllegalStateException("Context contains a duplicate tool call id: " + call.id());
                }
            }
        }

        Set<String> answered = new HashSet<>();
        for (int index = 0; index < messages.size(); index++) {
            if (!(messages.get(index) instanceof ToolMessageEntity tool)) continue;

            String id = tool.getId() == null ? null : String.valueOf(tool.getId());
            Integer owner = owners.get(id);
            if (owner == null) {
                log.warn("Dropping orphan tool result: id={}, name={}", id, tool.getName());
                continue;
            }

            if (owner >= index || !answered.add(id)) {
                throw new IllegalStateException("Context contains an out-of-order or duplicate tool result: " + id);
            }
        }

        return new History(owners, latestAssistant, !answered.containsAll(owners.keySet()));
    }

    private List<Message> project(List<Message> input, History history, boolean dropHistory) {
        List<Message> product = new ArrayList<>(input.size());

        for (Message message : input) {
            if (message instanceof SystemMessageEntity) product.add(message);
        }

        // Emit each non-system message in its own original slot, including non-adjacent results.
        for (int index = 0; index < input.size(); index++) {
            Message message = input.get(index);
            if (message instanceof SystemMessageEntity) continue;

            if (message instanceof AiMessageEntity ai) {
                boolean keepCalls = !dropHistory || index == history.latestAssistant();
                List<ToolCallRequest> calls = keepCalls ? ai.getToolCalls() : List.of();

                if ((ai.getText() == null || ai.getText().isBlank()) && (calls == null || calls.isEmpty())) {
                    continue;
                }

                product.add(rewriteAssistant(ai, calls));
            } else if (message instanceof ToolMessageEntity tool) {
                Integer owner = history.owners().get(tool.getId() == null ? null : String.valueOf(tool.getId()));
                if (owner == null) continue;

                if (owner == history.latestAssistant()) {
                    product.add(tool);
                } else if (!dropHistory) {
                    product.add(skeleton(tool));
                }
            } else {
                product.add(message);
            }
        }

        return product;
    }

    private AiMessageEntity rewriteAssistant(AiMessageEntity source, List<ToolCallRequest> calls) {
        boolean thinkingFree = (source.getThinking() == null || source.getThinking().isBlank())
                && (source.getAttributes() == null || source.getAttributes().isEmpty());

        if (thinkingFree && Objects.equals(source.getToolCalls(), calls)) return source;

        // Rewriting a prefix invalidates later thinking signatures; raw snapshots stay untouched.
        return AiMessageEntity.builder()
                .text(source.getText())
                .toolCalls(calls == null ? List.of() : List.copyOf(calls))
                .attributes(Map.of())
                .build();
    }

    private ToolMessageEntity skeleton(ToolMessageEntity source) {
        // Derive the placeholder from execution metadata, never from the previous output's spelling.
        String output = "[output omitted: status=" + (source.isError() ? "error" : "success")
                + (source.getExitCode() == null ? "" : ", exit=" + source.getExitCode()) + "]";

        if (output.equals(source.text())) return source;

        return ToolMessageEntity.builder()
                .id(source.getId())
                .name(source.getName())
                .isError(source.isError())
                .exitCode(source.getExitCode())
                .toolOutput(output)
                .build();
    }

    private void checkBudget(List<Message> messages) {
        int tokens = tokenizer.count(messages);
        if (tokens <= agentConfig.maxTokens()) return;

        throw new TokenBudgetExceededException("Context compaction failed: model summarization is unavailable or "
                + "could not meet the input budget. Protected messages, historical assistant text, and the latest "
                + "tool results still use " + tokens + " tokens, exceeding the input budget of "
                + agentConfig.maxTokens() + " tokens. Increase the context budget or reduce system prompts, "
                + "protected application state, and historical input.");
    }
}
