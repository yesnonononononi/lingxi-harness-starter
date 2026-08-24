package com.summit.runtime.model;


import com.summit.harnesscore.conversation.event.RuntimeEventPublisher;
import com.summit.harnesscore.model.StreamingModelInvoker;
import dev.langchain4j.model.StreamingResponseHandler;
import dev.langchain4j.model.chat.StreamingChatModel;
import dev.langchain4j.model.chat.request.ChatRequest;
import dev.langchain4j.model.output.Response;
import lombok.Getter;
import lombok.RequiredArgsConstructor;

@Getter
@RequiredArgsConstructor
public class DefaultStreamingModelInvoker implements StreamingModelInvoker {
    private final StreamingChatModel model;
    private final RuntimeEventPublisher runtimeEventPublisher;



    @Override
    public void invoke(ChatRequest chatRequest) {
        StreamingResponseHandler<Object> handler = new StreamingResponseHandler<>() {
            @Override
            public void onNext(String token) {

            }

            @Override
            public void onComplete(Response<Object> response) {
                StreamingResponseHandler.super.onComplete(response);
            }

            @Override
            public void onError(Throwable error) {

            }
        };
    }
}
