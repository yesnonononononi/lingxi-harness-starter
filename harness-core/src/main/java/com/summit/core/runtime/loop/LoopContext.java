package com.summit.core.runtime.loop;

import com.summit.core.conversation.message.Message;

import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.function.Consumer;

/**
 * Execution identity and controlled message append operation for synchronous loop callbacks.
 */
public record LoopContext(String executionId,Map<String, Object> attributes,
                          // It will be put into context as the message of user
                          Consumer<List<? extends Message>> appendMessage) {
    public LoopContext(String executionId, Map<String, Object> attributes,
                       Consumer<List<? extends Message>> appendMessage) {
        this.executionId = executionId;
        this.attributes = attributes == null ? Map.of()
                : Map.copyOf(attributes);
        this.appendMessage = Objects.requireNonNull(appendMessage, "appendMessage");
    }

    /**
     * Call only on the execution thread during a callback; do not retain for async mutation.
     */
    public void appendMessage(List<? extends Message> messages) {
        appendMessage.accept(messages);
    }
}
