package com.summit.core.compact;

import com.summit.core.conversation.message.Message;
import com.summit.core.conversation.message.content.Content;
import com.summit.core.tool.ToolDefinition;

import java.util.List;

public interface Tokenizer {
     int count(List<Message> messages);
     /** Estimates one content part without message framing; unsupported parts must not report zero. */
     default int count(Content content) {
          throw new UnsupportedOperationException("Content token estimation is not supported");
     }

     default int countText(String text) {
          return text == null ? 0 : text.length() / 3;
     }
     default int count(List<Message> messages, List<ToolDefinition<?>> tools) {
          return count(messages);
     }

     default int count(Message chatMessage){
          return count(List.of(chatMessage));
     };


    /**
     * Truncates the given output to the specified maximum size.
     *
     * <p>The kept part is always the prefix: the budget is spent from the head and everything past it
     * is dropped behind a truncation marker. Keeping a tail as well would make the result read as a
     * continuous document while whole regions are silently missing.</p>
     *
     * @param output  The output to be truncated.
     * @param maxOutput The maximum size of the output in TOKENS. Outputs whose token
     *                  estimate does not exceed this budget are returned unchanged.
     */
    String truncate(String output, int maxOutput);

    double calcCurrentTokenRatio(List<Message> messages,List<ToolDefinition<?>> tools, int maxTokens);


    ContextUsageMetric usage(List<Message> messages,List<ToolDefinition<?>> tools, Integer maxTokens);
}
