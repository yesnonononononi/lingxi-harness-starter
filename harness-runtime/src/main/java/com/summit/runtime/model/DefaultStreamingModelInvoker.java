package com.summit.runtime.model;


import com.summit.core.conversation.api.ChatResponseEntity;
import com.summit.core.model.ModelChatCommand;
import com.summit.core.model.ModelInvoker;
import com.summit.core.model.streaming.StreamingChatModel;
import lombok.Getter;
import lombok.RequiredArgsConstructor;

@Getter
@RequiredArgsConstructor
public class DefaultStreamingModelInvoker implements ModelInvoker {
    private final StreamingChatModel model;


    @Override
    public ChatResponseEntity invoke(ModelChatCommand chatCommand) throws Exception {
        if (!chatCommand.streaming()) {
            throw new IllegalStateException("streaming invoker only accepts a streaming model command");
        }
        StreamingModelResponseBehaveDecider handler = (StreamingModelResponseBehaveDecider) chatCommand.streamingChatResponseHandler();
        if (handler == null) {
            throw new IllegalStateException("streamingChatResponseHandler must not be null for a streaming invocation");
        }
        this.model.chat(chatCommand.chatRequest(), handler);
        return handler.getStreamingResponseContext().future().join();
    }
}
