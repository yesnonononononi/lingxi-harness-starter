package com.summit.core.conversation.api;

import com.summit.core.conversation.message.AiMessageEntity;
import com.summit.core.conversation.message.ToolMessageEntity;

import java.util.List;
import java.util.Map;

public interface ConversationTranscriptSink {
    /**
     * Appends one model round that has been accepted into the conversation.
     * Implementations must copy or serialize the supplied mutable message objects before returning,
     * because later context compaction is allowed to mutate them.
     */
    void appendRound(String executionId, AiMessageEntity aiMessage, List<ToolMessageEntity> toolMessages);

    /** Passes the same selected metadata as runtime events, preserving existing sink implementations. */
    default void appendRound(String executionId, AiMessageEntity aiMessage,
                             List<ToolMessageEntity> toolMessages, Map<String, Object> eventMetaData) {
        appendRound(executionId, aiMessage, toolMessages);
    }

    /** Preserves the model invocation identity while keeping existing sinks compatible. */
    default void appendRound(String executionId, AiMessageEntity aiMessage,
                             List<ToolMessageEntity> toolMessages, String responseId,
                             Map<String, Object> eventMetaData) {
        appendRound(executionId, aiMessage, toolMessages, eventMetaData);
    }
}
