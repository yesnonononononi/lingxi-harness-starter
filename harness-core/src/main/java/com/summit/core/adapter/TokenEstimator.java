package com.summit.core.adapter;

import com.summit.core.conversation.message.Message;
import com.summit.core.tool.ToolDefinition;

import java.util.List;

/**
 * Neutral token estimation SPI, independent of the provider or encoding implementation.
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

    /** Estimates the messages and the model-facing tool definitions in one request. */
    default int estimateMessages(List<Message> messages, List<ToolDefinition<?>> tools) {
        return estimateMessages(messages);
    }
}
