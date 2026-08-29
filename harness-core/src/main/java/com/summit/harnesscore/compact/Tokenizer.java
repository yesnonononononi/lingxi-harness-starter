package com.summit.harnesscore.compact;

import com.summit.harnesscore.conversation.message.Message;

import java.util.List;

public interface Tokenizer {
     int count(List<Message> messages);

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
}
