package com.summit.adapter.langchain4j.model;

import com.summit.adapter.langchain4j.codec.MessageCodecAdapter;
import com.summit.adapter.langchain4j.codec.ToolCodecAdapter;
import com.summit.core.adapter.MessageCodec;
import com.summit.core.adapter.ToolCodec;
import com.summit.core.conversation.api.ChatRequestEntity;
import com.summit.core.conversation.api.ChatResponseEntity;
import com.summit.core.model.ChatModel;
import dev.langchain4j.agent.tool.ToolSpecification;
import dev.langchain4j.data.message.ChatMessage;
import dev.langchain4j.model.chat.request.ChatRequest;
import dev.langchain4j.model.chat.response.ChatResponse;

import java.util.List;

/**
 * Adapts the langchain4j {@link dev.langchain4j.model.chat.ChatModel ChatModel}
 * to the core {@link ChatModel}. Message/tool conversions go through the core codec interfaces.
 */
public class ChatModelAdapter implements ChatModel {

    private final dev.langchain4j.model.chat.ChatModel delegate;
    private final MessageCodec<ChatMessage, ChatResponse> messageCodec;
    private final ToolCodec<ToolSpecification> toolCodec;

    public ChatModelAdapter(dev.langchain4j.model.chat.ChatModel delegate) {
        this(delegate, new MessageCodecAdapter(), new ToolCodecAdapter());
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
