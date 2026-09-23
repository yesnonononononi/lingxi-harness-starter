package com.summit.adapter.langchain4j.codec;

import com.summit.core.conversation.api.ChatResponseEntity;
import com.summit.core.conversation.message.Message;

import java.util.List;

/**
 * Converts between the harness message model and LangChain4j message/response types.
 *
 * @param <M> LangChain4j message type
 * @param <R> LangChain4j response type
 */
public interface MessageCodec<M, R> {

    M toFramework(Message message);

    List<M> toFramework(List<? extends Message> messages);

    ChatResponseEntity toChatResponseEntity(R frameworkResponse);
}
