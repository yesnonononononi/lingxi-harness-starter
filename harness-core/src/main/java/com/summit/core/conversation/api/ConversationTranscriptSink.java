package com.summit.core.conversation.api;

import com.summit.core.conversation.message.AiMessageEntity;
import com.summit.core.conversation.message.ToolMessageEntity;

import java.util.List;
import java.util.Map;

public interface ConversationTranscriptSink {
    /**
     * Appends one model round that has been accepted into the conversation.
     *
     * <p>Implementations must copy or serialize the supplied mutable message objects before
     * returning, because later context compaction is allowed to mutate them.</p>
     *
     * <p>The model invocation identity is passed on the only entry point. The transcript is keyed on
     * the response id, so an identity-free overload is exactly what let a sink silently record a
     * weaker round. A response that carries no id still reaches a sink as {@code null}: the sink
     * decides whether to reject or degrade, but it cannot be bypassed.</p>
     *
     * @param responseId    the model response that produced this round, or {@code null} when the
     *                      response carries none
     * @param eventMetaData the request's selected metadata, carrying session and turn ownership
     */
    void appendRound(String executionId, AiMessageEntity aiMessage, List<ToolMessageEntity> toolMessages,
                     String responseId, Map<String, Object> eventMetaData);
}
