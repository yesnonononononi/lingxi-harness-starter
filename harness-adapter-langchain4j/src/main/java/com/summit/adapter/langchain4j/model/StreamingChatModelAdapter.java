package com.summit.adapter.langchain4j.model;

import com.summit.adapter.langchain4j.codec.MessageCodecAdapter;
import com.summit.adapter.langchain4j.codec.ToolCodecAdapter;
import com.summit.core.adapter.MessageCodec;
import com.summit.core.adapter.ToolCodec;
import com.summit.core.conversation.api.ChatRequestEntity;
import com.summit.core.conversation.api.ChatResponseEntity;
import com.summit.core.model.StreamingChatModel;
import com.summit.core.model.StreamingChatResponseHandler;
import dev.langchain4j.agent.tool.ToolSpecification;
import dev.langchain4j.data.message.ChatMessage;
import dev.langchain4j.model.chat.request.ChatRequest;
import dev.langchain4j.model.chat.response.ChatResponse;
import dev.langchain4j.model.chat.response.PartialResponse;
import dev.langchain4j.model.chat.response.PartialResponseContext;
import dev.langchain4j.model.chat.response.PartialThinking;
import dev.langchain4j.model.chat.response.PartialThinkingContext;

import java.util.List;

/**
 * Adapts the langchain4j {@link dev.langchain4j.model.chat.StreamingChatModel StreamingChatModel}
 * to the core {@link StreamingChatModel}. Streaming callback signatures differ per framework,
 * so the bridging stays inside this module and only the core handler interface is exposed.
 */
public class StreamingChatModelAdapter implements StreamingChatModel {

    private final dev.langchain4j.model.chat.StreamingChatModel delegate;
    private final MessageCodec<ChatMessage, ChatResponse> messageCodec;
    private final ToolCodec<ToolSpecification> toolCodec;

    public StreamingChatModelAdapter(dev.langchain4j.model.chat.StreamingChatModel delegate) {
        this(delegate, new MessageCodecAdapter(), new ToolCodecAdapter());
    }

    public StreamingChatModelAdapter(dev.langchain4j.model.chat.StreamingChatModel delegate,
                                     MessageCodec<ChatMessage, ChatResponse> messageCodec,
                                     ToolCodec<ToolSpecification> toolCodec) {
        this.delegate = delegate;
        this.messageCodec = messageCodec;
        this.toolCodec = toolCodec;
    }

    @Override
    public void chat(ChatRequestEntity request, StreamingChatResponseHandler handler) {
        ChatRequest chatRequest = ChatRequestBuilder.buildRequest(request, messageCodec, toolCodec);
        delegate.chat(chatRequest, new StreamingHandlerBridge(handler, messageCodec));
    }

    private static class StreamingHandlerBridge implements dev.langchain4j.model.chat.response.StreamingChatResponseHandler {

        private final StreamingChatResponseHandler target;
        private final MessageCodec<ChatMessage, ChatResponse> messageCodec;

        private StreamingHandlerBridge(StreamingChatResponseHandler target,
                                       MessageCodec<ChatMessage, ChatResponse> messageCodec) {
            this.target = target;
            this.messageCodec = messageCodec;
        }

        @Override
        public void onPartialResponse(PartialResponse partialResponse, PartialResponseContext context) {
            target.onPartialResponse(partialResponse.text());
        }

        @Override
        public void onPartialThinking(PartialThinking partialThinking, PartialThinkingContext context) {
            target.onPartialThinking(partialThinking.text());
        }

        @Override
        public void onCompleteResponse(ChatResponse chatResponse) {
            ChatResponseEntity entity = messageCodec.toChatResponseEntity(chatResponse);
            target.onFinalResponse(entity);
        }

        @Override
        public void onError(Throwable error) {
            target.onError(error);
        }
    }
}
