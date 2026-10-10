package com.summit.core.runtime.loop;

import com.summit.core.agent.Execution;
import com.summit.core.conversation.message.Message;
import lombok.AllArgsConstructor;
import lombok.AccessLevel;
import lombok.Data;
import java.util.List;
import java.util.Objects;
import java.util.function.Consumer;
import java.util.function.IntSupplier;
import java.util.function.Supplier;

/**
 * Execution identity and controlled message append operation for synchronous loop callbacks.
 */
@Data
@AllArgsConstructor(access = AccessLevel.PRIVATE)
public class LoopContext {
        private final LoopMessages loopMessages;
        private final ExecutionControlSignal signal;
        private final Supplier<Integer> consecutiveCompactTurns;
        // It will be put into context as the message of user
        private final Consumer<List<? extends Message>> appendMessage;

    public LoopContext(LoopMessages loopMessages, ExecutionControlSignal signal,
                       Integer consecutiveCompactTurns, Consumer<List<? extends Message>> appendMessage) {
        this(loopMessages, signal, () -> consecutiveCompactTurns, appendMessage);
    }

    public static LoopContext withCompactionCounter(LoopMessages loopMessages, ExecutionControlSignal signal,
                                                    IntSupplier counter, Consumer<List<? extends Message>> appendMessage) {
        Objects.requireNonNull(counter, "counter");
        return new LoopContext(loopMessages, signal, counter::getAsInt, appendMessage);
    }

    /**
     * Committed consecutive dedicated compaction rounds in this run. Updated before onLoopEnd;
     * a committed non-compaction round resets it. A resumed run starts at zero.
     */
    public Integer getConsecutiveCompactTurns() {
        return consecutiveCompactTurns.get();
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
        return loopMessages.getExecution().getModelAttempts();
    }

    /**
     * Call only on the execution thread during a callback; do not retain for async mutation.
     */
    public void appendMessage(List<? extends Message> messages) {
        appendMessage.accept(messages);
    }
}
