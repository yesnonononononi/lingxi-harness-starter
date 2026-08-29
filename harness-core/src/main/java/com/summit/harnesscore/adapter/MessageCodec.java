package com.summit.harnesscore.adapter;

import com.summit.harnesscore.conversation.api.ChatResponseEntity;
import com.summit.harnesscore.conversation.message.Message;

import java.util.List;

/**
 * Message codec SPI.
 *
 * @param <M> Message types of specific model frameworks (such as ChatMessage in langchain4j)
 * @param <R> Response types of specific model frameworks (such as ChatResponse in langchain4j)
 */
public interface MessageCodec<M, R> {

    /**
     * core message -> framework message.
     */
    M toFramework(Message message);

    /**
     * core message list -> framework message list.
     */
    List<M> toFramework(List<? extends Message> messages);

    /**
     * framework response -> core response (containing AiMessage's toolCalls and TokenUsage).
     */
     ChatResponseEntity toChatResponseEntity(R frameworkResponse);
}
