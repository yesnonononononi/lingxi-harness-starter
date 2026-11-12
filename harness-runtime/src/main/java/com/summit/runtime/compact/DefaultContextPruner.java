package com.summit.runtime.compact;

import com.summit.core.agent.Execution;
import com.summit.core.compact.ContextAttachmentProvider;
import com.summit.core.compact.ContextCompactRequest;
import com.summit.core.compact.ContextCompacter;
import com.summit.core.compact.Tokenizer;
import com.summit.core.conversation.api.ToolCallRequest;
import com.summit.core.conversation.event.ContextUpdateEvent;
import com.summit.core.conversation.message.*;
import com.summit.core.exception.TokenBudgetExceededException;
import com.summit.core.runtime.loop.ContextUsageReporter;
import com.summit.runtime.agent.AgentConfig;
import lombok.extern.slf4j.Slf4j;

import java.util.*;

/**
 * Retains user messages and historical assistant text, followed by the complete latest user turn.
 * Only an oversized latest turn occupying more than half the candidate may be reduced further,
 * keeping its assistant text and the last assistant's complete tool group.
 */
@Slf4j
public class DefaultContextPruner implements ContextCompacter {

    private record History(Map<String, Integer> owners, int latestUser, int latestAssistant, boolean pending) {
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
            checkBudget(execution, source);
            return false;
        }

        List<Message> input = new ArrayList<>(source);
        attachments.attachment(execution.getId())
                .filter(value -> !value.isBlank())
                .filter(value -> source.stream().noneMatch(message ->
                        message instanceof SystemMessageEntity && message.text().contains(value)))
                .ifPresent(value -> input.add(SystemMessageEntity.builder().text(value).build()));

        boolean compactLatest = false;
        List<Message> product = project(input, history, false);
        int tokens = usage.countInput(execution, product);
        if (atThreshold(tokens)) {
            int latestTurnTokens = tokenizer.count(latestTurn(product));
            // Tool definitions consume the request budget but cannot be pruned with a user turn.
            if ((long) latestTurnTokens * 2 <= tokenizer.count(product)) {
                throw budgetExceeded(tokens, "The latest user turn does not occupy more than half the pruned context.");
            }
            compactLatest = true;
            product = project(input, history, true);
        }

        checkBudget(execution, product);

        if (indexHistory(product).pending()) {
            throw new IllegalStateException("Context pruning left an unanswered tool call");
        }

        // Build and validate both candidates before changing either messages or the persisted stage.
        boolean changed = !source.equals(product);
        execution.setMessages(product);
        execution.setCompactionState(execution.getCompactionState().withPruneGeneration(
                Math.max(execution.getCompactionState().pruneGeneration(), compactLatest ? 2 : 1)));

        log.info("Context prune completed: executionId={}, stage={}, changed={}",
                execution.getId(), compactLatest ? 2 : 1, changed);

        return changed;
    }

    private History indexHistory(List<Message> messages) {
        Map<String, Integer> owners = new HashMap<>();
        int latestUser = -1;
        int latestAssistant = -1;

        for (int index = 0; index < messages.size(); index++) {
            if (messages.get(index) instanceof UserMessageEntity user && !user.hasCompactionMarker()) latestUser = index;
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

        return new History(owners, latestUser, latestAssistant, !answered.containsAll(owners.keySet()));
    }

    private List<Message> project(List<Message> input, History history, boolean compactLatest) {
        List<Message> product = new ArrayList<>(input.size());
        // With no user message, treat the entire non-system history as the current turn.
        int keepFrom = Math.max(0, history.latestUser());
        if (compactLatest) keepFrom = Math.max(keepFrom, history.latestAssistant());
        // Dynamic context is re-rendered every round; only the newest block carries live state. The
        // newest one is kept unconditionally, so pruning never drops the background knowledge —
        // it only drops the superseded copies.
        int latestContext = latestContextIndex(input);

        for (Message message : input) {
            if (message instanceof SystemMessageEntity) product.add(message);
        }

        // Emit each non-system message in its own original slot, including non-adjacent results.
        for (int index = 0; index < input.size(); index++) {
            Message message = input.get(index);
            if (message instanceof SystemMessageEntity) continue;

            if (message instanceof AiMessageEntity ai) {
                boolean keepCalls = index >= keepFrom;
                List<ToolCallRequest> calls = keepCalls ? ai.getToolCalls() : List.of();

                if ((ai.getText() == null || ai.getText().isBlank()) && (calls == null || calls.isEmpty())) {
                    continue;
                }

                product.add(rewriteAssistant(ai, calls));
            } else if (message instanceof ToolMessageEntity tool) {
                Integer owner = history.owners().get(tool.getId() == null ? null : String.valueOf(tool.getId()));
                if (owner == null) continue;

                if (owner >= keepFrom) product.add(tool);
            } else if (message instanceof UserMessageEntity user) {
                if (user.hasContextContract() && index != latestContext) continue;
                Message retained = user.retainForCompaction(index < history.latestAssistant());
                if (retained != null) product.add(retained);
            } else {
                product.add(message);
            }
        }

        return product;
    }

    /** Index of the newest per-round context block, or -1 when this history has none. */
    private static int latestContextIndex(List<Message> messages) {
        for (int index = messages.size() - 1; index >= 0; index--) {
            if (messages.get(index) instanceof UserMessageEntity user && user.hasContextContract()) {
                return index;
            }
        }
        return -1;
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


    private List<Message> latestTurn(List<Message> messages) {
        for (int index = messages.size() - 1; index >= 0; index--) {
            if (messages.get(index) instanceof UserMessageEntity user && !user.hasCompactionMarker()) {
                return messages.subList(index, messages.size());
            }
        }
        return messages.stream().filter(message -> !(message instanceof SystemMessageEntity)).toList();
    }

    private void checkBudget(Execution execution, List<Message> messages) {
        int tokens = usage.countInput(execution, messages);
        if (!atThreshold(tokens)) return;

        throw budgetExceeded(tokens, "The retained context cannot be reduced below the compaction threshold.");
    }

    private boolean atThreshold(int tokens) {
        return tokens / (double) agentConfig.maxTokens() >= agentConfig.compactionThreshold();
    }

    private TokenBudgetExceededException budgetExceeded(int tokens, String reason) {
        return new TokenBudgetExceededException("Context compaction failed: " + reason
                + " Protected messages, user messages, assistant text, retained tool groups, and exposed tool definitions still use "
                + tokens + " tokens; the compaction threshold is " + agentConfig.compactionThreshold()
                + " of the input budget of " + agentConfig.maxTokens()
                + " tokens. Increase the context budget or reduce system prompts, protected application state, "
                + "and historical input.");
    }
}
