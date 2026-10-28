package com.summit.adapter.langchain4j.model;

import com.summit.core.conversation.api.ChatRequestEntity;
import com.summit.core.conversation.api.ChatResponseEntity;
import com.summit.core.conversation.event.TokenInfo;
import com.summit.core.conversation.message.UserMessageEntity;
import com.summit.core.model.streaming.StreamingChatResponseHandler;
import com.summit.core.model.streaming.StreamingHandler;
import dev.langchain4j.data.message.AiMessage;
import dev.langchain4j.model.chat.request.ChatRequest;
import dev.langchain4j.model.chat.response.ChatResponse;
import dev.langchain4j.model.chat.response.ChatResponseMetadata;
import dev.langchain4j.model.openai.OpenAiTokenUsage;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;

class ModelTokenUsagePropagationTest {
    @Test
    void synchronousAndStreamingAdaptersPreserveTheSameDetailedUsage() {
        ChatResponse response = ChatResponse.builder().aiMessage(AiMessage.from("answer"))
                .metadata(ChatResponseMetadata.builder().tokenUsage(OpenAiTokenUsage.builder()
                        .inputTokenCount(100).outputTokenCount(30).totalTokenCount(130)
                        .inputTokensDetails(OpenAiTokenUsage.InputTokensDetails.builder().cachedTokens(60).build())
                        .outputTokensDetails(OpenAiTokenUsage.OutputTokensDetails.builder().reasoningTokens(12).build())
                        .build()).build()).build();
        ChatRequestEntity request = ChatRequestEntity.builder()
                .messages(List.of(UserMessageEntity.from("question"))).build();
        var sync = new ChatModelAdapter(new dev.langchain4j.model.chat.ChatModel() {
            @Override public ChatResponse chat(ChatRequest ignored) { return response; }
        }).chat(request);
        AtomicReference<ChatResponseEntity> streamed = new AtomicReference<>();
        new StreamingChatModelAdapter(new dev.langchain4j.model.chat.StreamingChatModel() {
            @Override public void chat(ChatRequest ignored,
                    dev.langchain4j.model.chat.response.StreamingChatResponseHandler handler) {
                handler.onCompleteResponse(response);
            }
        }).chat(request, new StreamingChatResponseHandler() {
            public void onPartialResponse(String text, StreamingHandler handle) {}
            public void onPartialThinking(String text, StreamingHandler handle) {}
            public void onFinalResponse(ChatResponseEntity value) { streamed.set(value); }
            public void onError(Throwable error) { fail(error); }
        });
        assertNotNull(streamed.get());
        TokenInfo expected = new TokenInfo(100, 30, 130, 12, 60);
        assertEquals(expected, TokenInfo.from(sync.getTokenUsage()));
        assertEquals(expected, TokenInfo.from(streamed.get().getTokenUsage()));
    }
}
