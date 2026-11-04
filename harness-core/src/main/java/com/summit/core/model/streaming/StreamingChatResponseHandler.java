package com.summit.core.model.streaming;

import com.summit.core.conversation.api.ChatResponseEntity;

public interface StreamingChatResponseHandler {
    /** Registers a handle before the first delta so a pending request can be cancelled. */
    default void onStreamStart(StreamingHandler streamingHandler) {
    }

     void onPartialResponse(String text,StreamingHandler streamingHandler);
     void onPartialThinking(String thinking,StreamingHandler streamingHandler);

    /**
     * A tool call fragment. The ID, name, and arguments may arrive in separate fragments.
     *
     * <p>Tool calls carry no text, so without this hook a control request arriving while the model
     * is emitting one would stay unobserved until the round's stream had been consumed in full —
     * the one window where a cancel could not cut the call short. The chunk itself is not surfaced
     * as a runtime event; the assembled call is reported once the round's response arrives.</p>
     */
    default void onPartialToolCall(int index, String id, String name, String partialArguments, StreamingHandler streamingHandler) {
    }

    void onFinalResponse(ChatResponseEntity chatResponseEntity);
    void onError(Throwable err);
}
