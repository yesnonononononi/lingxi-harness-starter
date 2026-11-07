package com.summit.core.compact;

import com.summit.core.conversation.message.Message;
import com.summit.core.tool.ToolDefinition;

import java.util.List;

public interface Tokenizer {
     int count(List<Message> messages);

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
     * @param output  The output to be truncated.
     * @param maxOutput The maximum size of the output in TOKENS. Outputs whose token
     *                  estimate does not exceed this budget are returned unchanged.
     */
    String truncate(String output, int maxOutput);

    double calcCurrentTokenRatio(List<Message> messages,List<ToolDefinition<?>> tools, int maxTokens);


    ContextUsageMetric usage(List<Message> messages,List<ToolDefinition<?>> tools, Integer maxTokens);
}
