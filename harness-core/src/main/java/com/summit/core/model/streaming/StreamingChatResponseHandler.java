package com.summit.core.model.streaming;

import com.summit.core.conversation.api.ChatResponseEntity;

public interface StreamingChatResponseHandler {
     void onPartialResponse(String text,StreamingHandler streamingHandler);
     void onPartialThinking(String thinking,StreamingHandler streamingHandler);

    /**
     * A chunk of the arguments of a tool call the model is streaming.
     *
     * <p>Tool calls carry no text, so without this hook a control request arriving while the model
     * is emitting one would stay unobserved until the round's stream had been consumed in full —
     * the one window where a cancel could not cut the call short. The chunk itself is not surfaced
     * as a runtime event; the assembled call is reported once the round's response arrives.</p>
     */
    default void onPartialToolCall(String partialArguments, StreamingHandler streamingHandler) {
    }

    void onFinalResponse(ChatResponseEntity chatResponseEntity);
    void onError(Throwable err);
}
