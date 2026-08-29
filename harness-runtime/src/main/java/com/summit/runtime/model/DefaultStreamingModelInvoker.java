package com.summit.runtime.model;


import com.summit.harnesscore.conversation.api.ChatResponseEntity;
import com.summit.harnesscore.conversation.event.RuntimeEventPublisher;
import com.summit.harnesscore.model.ModelChatCommand;
import com.summit.harnesscore.model.ModelInvoker;
import com.summit.harnesscore.model.StreamingChatModel;
import lombok.Getter;
import lombok.RequiredArgsConstructor;

@Getter
@RequiredArgsConstructor
public class DefaultStreamingModelInvoker implements ModelInvoker {
    private final StreamingChatModel model;
    private final RuntimeEventPublisher runtimeEventPublisher;


    @Override
    public ChatResponseEntity invoke(ModelChatCommand chatCommand) {
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
