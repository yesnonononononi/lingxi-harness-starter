package openAi;

import com.summit.core.conf.ModelConfig;
import com.summit.core.conversation.api.ChatRequestEntity;
import com.summit.core.conversation.api.ChatResponseEntity;
import com.summit.core.model.chat.ChatModel;
import com.summit.core.model.streaming.StreamingChatModel;
import com.summit.core.model.streaming.StreamingChatResponseHandler;
import com.summit.core.model.streaming.StreamingHandler;
import lombok.Builder;
import lombok.RequiredArgsConstructor;

import java.net.http.HttpClient;
import java.util.concurrent.atomic.AtomicBoolean;
@Builder
@RequiredArgsConstructor
public class DefaultChatModel implements ChatModel , StreamingChatModel {
    private final ModelConfig modelConfig;
    private final ModelChatResolver modelChatResolver;
    private final HttpClient httpClient;

    @Override
    public ChatResponseEntity chat(ChatRequestEntity request) {
        ModelConfig synchronous = modelConfig.toBuilder().stream(false).build();
        return modelChatResolver.resolve(httpClient, synchronous, request, null, null);
    }

    @Override
    public void chat(ChatRequestEntity request, StreamingChatResponseHandler handler) {
        AtomicBoolean cancelled = new AtomicBoolean();
        Thread worker = Thread.ofVirtual().unstarted(() -> {
            if (cancelled.get()) return;
            try {
                this.modelChatResolver.resolve(httpClient, modelConfig.toBuilder().stream(true).build(), request, null, handler);
            } catch (RuntimeException | Error error) {
                handler.onError(error);
            }
        });

        handler.onStreamStart(new StreamingHandler() {
            @Override
            public void cancel() {
                if (cancelled.compareAndSet(false, true)) worker.interrupt();
            }

            @Override
            public boolean isCancelled() {
                return cancelled.get();
            }
        });
        if (!cancelled.get()) worker.start();
    }
}
