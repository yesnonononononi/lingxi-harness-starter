package com.summit.core.conversation.api;

import com.summit.core.conversation.message.AiMessageEntity;
import com.summit.core.conversation.message.ToolMessageEntity;

import java.util.List;

public interface ConversationTranscriptSink {
    /**
     * Appends one model round that has been accepted into the conversation.
     * Implementations must copy or serialize the supplied mutable message objects before returning,
     * because later context compaction is allowed to mutate them.
     */
    void appendRound(String executionId, AiMessageEntity aiMessage, List<ToolMessageEntity> toolMessages);
}
