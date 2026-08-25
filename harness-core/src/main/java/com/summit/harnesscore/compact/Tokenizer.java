package com.summit.harnesscore.compact;

import dev.langchain4j.data.message.ChatMessage;

import java.util.List;

public interface Tokenizer {
     int count(List<ChatMessage> messages);

     default int count(ChatMessage chatMessage){
          return count(List.of(chatMessage));
     };


    /**
     * Truncates the given output to the specified maximum size.
     * @param output  The output to be truncated.
     * @param maxOutput The maximum size of the output in TOKENS. Outputs whose token
     *                  estimate does not exceed this budget are returned unchanged.
     */
    String truncate(String output, int maxOutput);
}
