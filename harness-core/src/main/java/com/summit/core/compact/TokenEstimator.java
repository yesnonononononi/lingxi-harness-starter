package com.summit.core.compact;

import com.summit.core.conversation.message.Message;
import com.summit.core.conversation.message.content.Content;
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


    /** Estimates one content part without message framing. */
    default int estimateContent(Content content) {
        throw new UnsupportedOperationException("Content token estimation is not supported");
    }

    /** Estimates the messages and the model-facing tool definitions in one request. */
    default int estimateMessages(List<Message> messages, List<ToolDefinition<?>> tools) {
        return estimateMessages(messages);
    }
}
