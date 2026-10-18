package com.summit.adapter.langchain4j.model;

import com.summit.adapter.langchain4j.codec.*;
import com.summit.core.conversation.api.ChatRequestEntity;
import com.summit.core.conversation.api.ChatResponseEntity;
import com.summit.core.model.chat.ChatModel;
import dev.langchain4j.agent.tool.ToolSpecification;
import dev.langchain4j.data.message.ChatMessage;
import dev.langchain4j.model.anthropic.AnthropicChatModel;
import dev.langchain4j.model.chat.request.ChatRequest;
import dev.langchain4j.model.chat.response.ChatResponse;

/**
 * Adapts the langchain4j {@link dev.langchain4j.model.chat.ChatModel ChatModel}
 * to the core {@link ChatModel}. Message/tool conversions stay inside this adapter module.
 */
public class ChatModelAdapter implements ChatModel {

    private final dev.langchain4j.model.chat.ChatModel delegate;
    private final MessageCodec<ChatMessage, ChatResponse> messageCodec;
    private final ToolCodec<ToolSpecification> toolCodec;

    public ChatModelAdapter(dev.langchain4j.model.chat.ChatModel delegate) {
        this(delegate, delegate instanceof AnthropicChatModel
                ? new AnthropicMessageCodecAdapter() : new OpenAiMessageCodecAdapter());
    }

    /** Use explicit codec injection when the delegate is a wrapper or a custom provider. */
    public ChatModelAdapter(dev.langchain4j.model.chat.ChatModel delegate,
                            MessageCodec<ChatMessage, ChatResponse> messageCodec) {
        this(delegate, messageCodec, new ToolCodecAdapter());
    }

    public ChatModelAdapter(dev.langchain4j.model.chat.ChatModel delegate,
                            MessageCodec<ChatMessage, ChatResponse> messageCodec,
                            ToolCodec<ToolSpecification> toolCodec) {
        this.delegate = delegate;
        this.messageCodec = messageCodec;
        this.toolCodec = toolCodec;
    }

    @Override
    public ChatResponseEntity chat(ChatRequestEntity request) {
        ChatRequest chatRequest = ChatRequestBuilder.buildRequest(request, messageCodec, toolCodec);
        ChatResponse chatResponse = delegate.chat(chatRequest);

        return messageCodec.toChatResponseEntity(chatResponse);
    }


}
