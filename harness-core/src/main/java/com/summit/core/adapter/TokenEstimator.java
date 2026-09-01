package com.summit.core.adapter;

import com.summit.core.conversation.message.Message;

import java.util.List;

/**
 * Neutral token estimation SPI. The specific implementation is provided by the adapter module (such as based on OpenAI BPE encoding).
 * The internal framework (such as the default implementation of Tokenizer, etc.) only depends on this interface.
 */
public interface TokenEstimator {

    /**
     * estimate the number of tokens in a segment of text.
     */
    int estimateText(String text);

    /**
     * estimate the number of tokens in a list of messages.
     */
    int estimateMessages(List<Message> messages);
}
