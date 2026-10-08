package com.summit.core.runtime.loop;

import com.summit.core.agent.Execution;
import com.summit.core.conversation.message.Message;

import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.function.Consumer;

/**
 * Execution identity and controlled message append operation for synchronous loop callbacks.
 */
public record LoopContext(
        Execution execution,
        ExecutionControlSignal signal,
        Integer consecutiveCompactTurns,
        Map<String, Object> attributes,

        // It will be put into context as the message of user
        Consumer<List<? extends Message>> appendMessage
) {
    public LoopContext( Execution execution, ExecutionControlSignal signal, Integer consecutiveCompactTurns, Map<String, Object> attributes,
                       Consumer<List<? extends Message>> appendMessage) {
        this.execution = execution;
        this.signal = signal;
        this.consecutiveCompactTurns = consecutiveCompactTurns;
        this.attributes = attributes == null ? Map.of()
                : Map.copyOf(attributes);
        this.appendMessage = Objects.requireNonNull(appendMessage, "appendMessage");
    }

    /**
     * The round this callback belongs to, counted from 0 across all resumes of the execution.
     *
     * <p>Derived rather than stored: the context is built once outside the loop and handed to every
     * hook unchanged, so any copy taken here would freeze the first round's value. The attempt
     * counter on {@link Execution} is already the live number — it is what
     * {@link RuntimeBoundaryChecker} judges the run against — so reading it keeps the two views of
     * "how far has this run got" from drifting apart.</p>
     */
    public int loopCount() {
        return execution.getModelAttempts();
    }

    /**
     * Call only on the execution thread during a callback; do not retain for async mutation.
     */
    public void appendMessage(List<? extends Message> messages) {
        appendMessage.accept(messages);
    }
}
