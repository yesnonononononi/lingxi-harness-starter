package com.summit.adapter.langchain4j.model;

import com.summit.adapter.langchain4j.codec.AnthropicMessageCodecAdapter;
import com.summit.core.conversation.api.ChatRequestEntity;
import com.summit.core.conversation.api.ChatResponseEntity;
import com.summit.core.conversation.event.TokenInfo;
import com.summit.core.conversation.message.UserMessageEntity;
import com.summit.core.model.streaming.StreamingChatResponseHandler;
import com.summit.core.model.streaming.StreamingHandler;
import dev.langchain4j.data.message.AiMessage;
import dev.langchain4j.model.anthropic.AnthropicChatModel;
import dev.langchain4j.model.anthropic.AnthropicStreamingChatModel;
import dev.langchain4j.model.anthropic.AnthropicTokenUsage;
import dev.langchain4j.model.chat.request.ChatRequest;
import dev.langchain4j.model.chat.response.ChatResponse;
import dev.langchain4j.model.chat.response.ChatResponseMetadata;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;

class AnthropicModelTokenUsagePropagationTest {
    private final ChatResponse response = ChatResponse.builder().aiMessage(AiMessage.from("answer"))
            .metadata(ChatResponseMetadata.builder().tokenUsage(AnthropicTokenUsage.builder()
                    .inputTokenCount(100).outputTokenCount(30)
                    .cacheCreationInputTokens(20).cacheReadInputTokens(60).build()).build()).build();

    @Test
    void selectsAnthropicCodecForSynchronousAndStreamingModels() {
        var sync = new AnthropicChatModel(AnthropicChatModel.builder().apiKey("test").modelName("test")) {
            @Override public ChatResponse chat(ChatRequest ignored) { return response; }
        };
        var streaming = new AnthropicStreamingChatModel(
                AnthropicStreamingChatModel.builder().apiKey("test").modelName("test")) {
            @Override public void chat(ChatRequest ignored,
                    dev.langchain4j.model.chat.response.StreamingChatResponseHandler handler) {
                handler.onCompleteResponse(response);
            }
        };
        assertAdapters(new ChatModelAdapter(sync), new StreamingChatModelAdapter(streaming));
    }

    @Test
    void acceptsExplicitAnthropicCodecForCustomDelegates() {
        var sync = new dev.langchain4j.model.chat.ChatModel() {
            @Override public ChatResponse chat(ChatRequest ignored) { return response; }
        };
        var streaming = new dev.langchain4j.model.chat.StreamingChatModel() {
            @Override public void chat(ChatRequest ignored,
                    dev.langchain4j.model.chat.response.StreamingChatResponseHandler handler) {
                handler.onCompleteResponse(response);
            }
        };
        assertAdapters(new ChatModelAdapter(sync, new AnthropicMessageCodecAdapter()),
                new StreamingChatModelAdapter(streaming, new AnthropicMessageCodecAdapter()));
    }

    private void assertAdapters(ChatModelAdapter sync, StreamingChatModelAdapter streaming) {
        ChatRequestEntity request = ChatRequestEntity.builder()
                .messages(List.of(UserMessageEntity.from("question"))).build();
        TokenInfo expected = new TokenInfo(180, 30, 210, 0, 60);
        assertEquals(expected, TokenInfo.from(sync.chat(request).getTokenUsage()));
        AtomicReference<ChatResponseEntity> streamed = new AtomicReference<>();
        streaming.chat(request, new StreamingChatResponseHandler() {
            public void onPartialResponse(String text, StreamingHandler handle) {}
            public void onPartialThinking(String text, StreamingHandler handle) {}
            public void onFinalResponse(ChatResponseEntity value) { streamed.set(value); }
            public void onError(Throwable error) { fail(error); }
        });
        assertNotNull(streamed.get());
        assertEquals(expected, TokenInfo.from(streamed.get().getTokenUsage()));
    }
}
