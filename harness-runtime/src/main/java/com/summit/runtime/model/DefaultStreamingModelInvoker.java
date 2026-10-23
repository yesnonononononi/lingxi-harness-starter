package com.summit.runtime.model;


import com.summit.core.conversation.api.ChatResponseEntity;
import com.summit.core.model.ModelChatCommand;
import com.summit.core.model.streaming.StreamingChatModel;
import com.summit.core.runtime.loop.suspension.ExecutionInterruptedException;
import lombok.Getter;
import lombok.RequiredArgsConstructor;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.CancellationException;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

@Getter
@RequiredArgsConstructor
public class DefaultStreamingModelInvoker extends AbstractInvoker {
    private final StreamingChatModel model;

    @Override
    protected ChatResponseEntity action(ModelChatCommand chatCommand) {

        if (!chatCommand.streaming()) {
            throw new IllegalStateException("streaming invoker only accepts a streaming model command");
        }

        StreamingModelResponseBehaveDecider handler = (StreamingModelResponseBehaveDecider) chatCommand.streamingChatResponseHandler();
        if (handler == null) {
            throw new IllegalStateException("streamingChatResponseHandler must not be null for a streaming invocation");
        }
        try {
            this.model.chat(chatCommand.chatRequest(), handler);
        } catch (RuntimeException | Error error) {
            handler.onError(error);
            throw error;
        }

        CompletableFuture<ChatResponseEntity> future = handler.getStreamingResponseContext().future();

        while (true) {
            handler.checkInterruption();
            try {
                ChatResponseEntity response = future.get(100, TimeUnit.MILLISECONDS);
                response.setResponseId(chatCommand.responseId());
                return response;
            } catch (TimeoutException ignored) {
                // Observe control even while the provider emits no deltas.
            } catch (InterruptedException error) {
                Thread.currentThread().interrupt();
                handler.checkInterruption();
                throw new ExecutionInterruptedException(ExecutionInterruptedException.Kind.CANCEL, "model invocation interrupted");
            } catch (CancellationException error) {
                handler.interrupt(ExecutionInterruptedException.Kind.CANCEL);
                throw error;
            } catch (ExecutionException error) {
                throw new CompletionException(error.getCause());
            }
        }
    }



}
